package com.block.hazard_api.routes;

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
import org.springframework.stereotype.Service;

/**
 * The whole pipeline for one trip (backend/CLAUDE.md "Backend pipeline"):
 * Google routes -> sample points + ETAs -> NWS forecast at each ETA -> weather bucket
 * -> scorer -> rank by shelf life left.
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

    // NWS lookups run 8 at a time; one by one, a long trip would take ~30 seconds.
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    public RoutePlanService(RoutesApiClient routesApi, NwsForecastClient forecasts,
                            WeatherBucketMapper bucketMapper, RouteScorer scorer) {
        this.routesApi = routesApi;
        this.forecasts = forecasts;
        this.bucketMapper = bucketMapper;
        this.scorer = scorer;
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
        List<ScoreRequest.Route> scoreRoutes = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            String id = "route-" + (i + 1); // route-1 is Google's recommended route
            CandidateRoute c = candidates.get(i);
            RouteWeather weather = weatherAlong(RouteSampler.sample(c.steps(), departure));
            candidateById.put(id, c);
            weatherById.put(id, weather);
            scoreRoutes.add(new ScoreRequest.Route(id, departure, c.durationSeconds() / 60.0, weather.points()));
        }
        ScoreResponse scores = scorer.score(new ScoreRequest(SUPPORTED_PRODUCE, TRUCK_TYPE, scoreRoutes));
        log.info("Scored {} routes with model {}", scoreRoutes.size(), scores.modelVersion());

        // Rank best first and build the route cards.
        List<RoutePlanResponse.PlannedRoute> cards = new ArrayList<>();
        int rank = 1;
        for (ScoreResponse.RouteScore s : RouteRanker.rank(scores.routes())) {
            CandidateRoute c = candidateById.get(s.routeId());
            RouteWeather w = weatherById.get(s.routeId());
            cards.add(new RoutePlanResponse.PlannedRoute(
                    rank++,
                    s.routeId(),
                    c.encodedPolyline(),
                    round1(c.distanceKm()),
                    round1(c.durationSeconds() / 60.0),
                    round1(s.predictedTotalDurationMin()),
                    round1(s.shelfLifeRemainingPct()),
                    round1(s.peakCargoTempC()),
                    List.of(), // filled in by Task 7
                    RouteReasons.reason(s.shelfLifeRemainingPct(), s.predictedDelayMin(),
                            w.pointsWithoutForecast(), w.points().size())));
        }
        return new RoutePlanResponse(scores.modelVersion(), cards);
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
    RouteWeather weatherAlong(List<SampledPoint> samples) {
        List<Future<Optional<PointForecast>>> futures = new ArrayList<>();
        for (SampledPoint sp : samples) {
            futures.add(pool.submit(() -> forecasts.forecastAt(sp.point(), sp.eta())));
        }
        List<PointForecast> found = new ArrayList<>(); // null where NWS had nothing
        int missing = 0;
        for (Future<Optional<PointForecast>> f : futures) {
            PointForecast pf = getQuietly(f);
            if (pf == null) missing++;
            found.add(pf);
        }

        List<ScoreRequest.Point> points = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            SampledPoint sp = samples.get(i);
            PointForecast pf = nearestFound(found, i);
            if (pf == null) {
                points.add(new ScoreRequest.Point(sp.point().lat(), sp.point().lng(), sp.eta(),
                        null, null, null, WeatherBucket.CLOUDY));
            } else {
                points.add(new ScoreRequest.Point(sp.point().lat(), sp.point().lng(), sp.eta(),
                        pf.temperatureC(), pf.windSpeedKmh(),
                        null, // precip_mm_per_hr: open team question, NWS hourly has no amount
                        bucketMapper.map(pf.shortForecast(), pf.precipChancePct())));
            }
        }
        if (missing > 0) log.info("No NWS forecast for {} of {} points; used fallback", missing, samples.size());
        return new RouteWeather(points, missing);
    }

    /** found.get(i) if present, else the closest index (either direction) that has one, else null. */
    static PointForecast nearestFound(List<PointForecast> found, int i) {
        for (int d = 0; d < found.size(); d++) {
            if (i - d >= 0 && found.get(i - d) != null) return found.get(i - d);
            if (i + d < found.size() && found.get(i + d) != null) return found.get(i + d);
        }
        return null;
    }

    private static PointForecast getQuietly(Future<Optional<PointForecast>> f) {
        try {
            return f.get().orElse(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static double round1(double x) {
        return Math.round(x * 10) / 10.0;
    }
}
