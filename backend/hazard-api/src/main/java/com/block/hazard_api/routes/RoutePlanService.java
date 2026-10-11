package com.block.hazard_api.routes;

import com.block.hazard_api.hazards.GeoUtil;
import com.block.hazard_api.hazards.HazardService;
import com.block.hazard_api.hazards.LatLng;
import com.block.hazard_api.risk.RiskService;
import com.block.hazard_api.scoring.RouteRanker;
import com.block.hazard_api.scoring.RouteScorer;
import com.block.hazard_api.scoring.ScoreRequest;
import com.block.hazard_api.scoring.ScoreResponse;
import com.block.hazard_api.weather.NwsForecastClient;
import com.block.hazard_api.weather.PointForecast;
import com.block.hazard_api.weather.WeatherBucket;
import com.block.hazard_api.weather.WeatherBucketMapper;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The whole pipeline for one trip (backend/CLAUDE.md "Backend pipeline"):
 * Google routes -> sample points + ETAs -> NWS forecast at each ETA -> weather bucket
 * -> scorer -> rank by shelf life left, plus the active hazards each route goes through.
 * The v2 risk model adds expected incident delay (included in the scored trip time) and
 * risk fields per route; if it fails, those fields are null and everything else is unchanged.
 */
@Service
public class RoutePlanService {

    private static final Logger log = LoggerFactory.getLogger(RoutePlanService.class);

    static final String SUPPORTED_PRODUCE = "strawberries";
    static final String TRUCK_TYPE = "reefer";

    private final RoutesApiClient routesApi;
    private final NwsForecastClient forecasts;
    private final WeatherBucketMapper bucketMapper;
    private final RouteScorer scorer;
    private final HazardService hazards;
    private final RiskService risk; // null = no risk fields

    // NWS lookups run 8 at a time; one by one, a long trip would take ~30 seconds.
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    /** Without the risk model: every risk field is null. */
    public RoutePlanService(RoutesApiClient routesApi, NwsForecastClient forecasts,
                            WeatherBucketMapper bucketMapper, RouteScorer scorer, HazardService hazards) {
        this(routesApi, forecasts, bucketMapper, scorer, hazards, null);
    }

    @Autowired
    public RoutePlanService(RoutesApiClient routesApi, NwsForecastClient forecasts,
                            WeatherBucketMapper bucketMapper, RouteScorer scorer, HazardService hazards,
                            RiskService risk) {
        this.routesApi = routesApi;
        this.forecasts = forecasts;
        this.bucketMapper = bucketMapper;
        this.scorer = scorer;
        this.hazards = hazards;
        this.risk = risk;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }

    /** Weather for every sampled point of one route, plus how many had no forecast. */
    record RouteWeather(List<ScoreRequest.Point> points, int pointsWithoutForecast) {}

    public RoutePlanResponse plan(RoutePlanRequest req) {
        validate(req); // before calling Google or the scorer

        // Google rejects traffic-aware requests in the past, so "now or earlier" means "in a minute".
        Instant soon = Instant.now().plus(Duration.ofMinutes(1));
        Instant departure = req.departureTime() == null || req.departureTime().isBefore(soon)
                ? soon : req.departureTime();

        List<CandidateRoute> candidates = routesApi.computeRoutes(req.origin(), req.destination(), departure);
        if (candidates.isEmpty()) {
            throw new BadRequestException("Google found no driving route between those addresses");
        }

        // Build one scorer request with every candidate route.
        Map<String, CandidateRoute> candidateById = new HashMap<>();
        Map<String, RouteWeather> weatherById = new HashMap<>();
        Map<String, RiskService.RouteRisk> riskById = new HashMap<>();
        List<ScoreRequest.Route> scoreRoutes = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            String id = "route-" + (i + 1); // route-1 is Google's recommended route
            CandidateRoute c = candidates.get(i);
            List<SampledPoint> samples = RouteSampler.sample(c.steps(), departure);
            RouteWeather weather = weatherAlong(samples);
            RiskService.RouteRisk routeRisk = riskAlong(id, samples);
            candidateById.put(id, c);
            weatherById.put(id, weather);
            riskById.put(id, routeRisk);
            // Expected incident delay is added to Google's time BEFORE the shelf-life calculation.
            double incidentDelayMin = routeRisk == null ? 0.0 : routeRisk.expectedIncidentDelayMin();
            scoreRoutes.add(new ScoreRequest.Route(id, departure, c.durationSeconds() / 60.0 + incidentDelayMin,
                    weather.points()));
        }
        ScoreResponse scores = scorer.score(new ScoreRequest(SUPPORTED_PRODUCE, TRUCK_TYPE, scoreRoutes));
        log.info("Scored {} routes with model {}", scoreRoutes.size(), scores.modelVersion());

        // Rank best first and build the route cards.
        List<RoutePlanResponse.PlannedRoute> cards = new ArrayList<>();
        int rank = 1;
        for (ScoreResponse.RouteScore s : RouteRanker.rank(scores.routes())) {
            CandidateRoute c = candidateById.get(s.routeId());
            RouteWeather w = weatherById.get(s.routeId());
            RiskService.RouteRisk r = riskById.get(s.routeId());
            List<RoutePlanResponse.HazardCrossed> crossed = hazardsAlong(c);
            cards.add(new RoutePlanResponse.PlannedRoute(
                    rank,
                    s.routeId(),
                    c.encodedPolyline(),
                    round1(c.distanceKm()),
                    round1(c.durationSeconds() / 60.0),
                    round1(s.predictedTotalDurationMin()),
                    round1(s.shelfLifeRemainingPct()),
                    round1(s.peakCargoTempC()),
                    crossed,
                    RouteReasons.reason(rank, s.shelfLifeRemainingPct(), s.predictedDelayMin(), crossed,
                            w.pointsWithoutForecast(), w.points().size()),
                    r == null ? null : r.riskScore(),
                    r == null ? null : r.incidentRiskRatio(),
                    r == null ? null : r.expectedIncidentDelayMin(),
                    r == null ? null : r.majorSlowdownProbability(),
                    r == null ? null : r.riskFlags(),
                    r == null ? null : r.riskReasons(),
                    r == null ? null : r.riskModelVersion(),
                    r == null ? null : r.riskLowConfidence()));
            rank++;
        }
        return new RoutePlanResponse(scores.modelVersion(), cards);
    }

    /**
     * v2 risk for one route, or null if risk is off or anything in it fails (logged, never thrown),
     * so a risk problem can't break route planning.
     */
    RiskService.RouteRisk riskAlong(String routeId, List<SampledPoint> samples) {
        if (risk == null || samples.isEmpty()) return null;
        try {
            List<LatLng> points = samples.stream().map(SampledPoint::point).toList();
            return risk.assess(samples, hazards.getHazards(HazardCrossings.boxAround(points)));
        } catch (Exception e) {
            log.warn("Risk model failed for {}; risk fields left empty", routeId, e);
            return null;
        }
    }

    /** Active hazards this route goes through (Task 7), checked on the full polyline. */
    private List<RoutePlanResponse.HazardCrossed> hazardsAlong(CandidateRoute c) {
        List<LatLng> line = GeoUtil.decodePolyline(c.encodedPolyline());
        if (line.isEmpty()) return List.of();
        return HazardCrossings.crossed(line, hazards.getHazards(HazardCrossings.boxAround(line)));
    }

    static void validate(RoutePlanRequest req) {
        if (req == null) throw new BadRequestException("Request body is missing");
        if (isBlank(req.origin())) throw new BadRequestException("origin is required");
        if (isBlank(req.destination())) throw new BadRequestException("destination is required");
        if (isBlank(req.produceType())) throw new BadRequestException("produceType is required");
        if (!SUPPORTED_PRODUCE.equals(req.produceType())) {
            throw new BadRequestException("produceType '" + req.produceType()
                    + "' is not yet supported (only strawberries)");
        }
    }

    /**
     * Looks up the forecast for every sampled point (in parallel) and turns it into scorer points.
     *
     * FALLBACK when NWS has no forecast for a point (outside the US, ETA more than ~7 days out,
     * or NWS errors): use the forecast of the NEAREST point on the same route that has one
     * (points are ~24 km and ~15-20 min apart, so their weather is similar). If no point on the
     * route has a forecast, use "cloudy" with no weather values: the table's neutral default,
     * adding no weather delay. Either way the route's reason text says how many points had none.
     */
    RouteWeathe