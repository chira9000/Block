package com.block.hazard_api.scoring;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The v1 model, run in Java. Every number comes from v1_model.json, and the steps follow
 * the model owner's model/export/reference_scorer.py IN THE SAME ORDER, so the results match
 * to the last decimal (checked against all files in model/export/test_cases/).
 *
 * Per route:
 *   1. weather delay: for each stretch between two points, the worse bucket's slowdown
 *   2. total time = Google's estimate + delay, cut into 15-minute steps
 *   3. cargo temperature (rear pallet) at the start of each step
 *   4. each step uses up (step hours / shelf life hours at that temperature) of the shelf life
 *
 * Active when scorer.type=coefficient.
 */
@Component
@ConditionalOnProperty(name = "scorer.type", havingValue = "coefficient")
public class CoefficientRouteScorer implements RouteScorer {

    static final String MODEL_RESOURCE = "/model/v1_model.json";

    private final V1Model model;

    /** Used by Spring: loads our copy of v1_model.json from the classpath. */
    public CoefficientRouteScorer() {
        this(loadModel());
    }

    /** Used by tests to pass a model in directly. */
    CoefficientRouteScorer(V1Model model) {
        this.model = model;
    }

    static V1Model loadModel() {
        try (InputStream in = CoefficientRouteScorer.class.getResourceAsStream(MODEL_RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing " + MODEL_RESOURCE + " on the classpath");
            return JsonMapper.builder().build().readValue(in, V1Model.class);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + MODEL_RESOURCE, e);
        }
    }

    @Override
    public ScoreResponse score(ScoreRequest request) {
        validateTrip(request);
        String pallet = model.cargoTemp().scoredPallet(); // "rear": warmest, spoils first
        List<ScoreResponse.RouteScore> results = new ArrayList<>();

        for (int r = 0; r < request.routes().size(); r++) {
            ScoreRequest.Route route = request.routes().get(r);
            validateRoute(route, r);

            // 1. Delay from weather, rounded FIRST so total = google + delay exactly
            double delayMin = round(routeDelayMin(route.points()));
            double totalMin = route.googleDurationMin() + delayMin;

            // 2 + 3. Cargo temperature at the start of each 15-minute step
            List<double[]> steps = splitIntoSteps(totalMin); // each is {startMin, lengthMin}
            List<Double> temps = new ArrayList<>();
            List<Double> hours = new ArrayList<>();
            for (double[] step : steps) {
                temps.add(cargoTempC(pallet, step[0] / 1440));
                hours.add(step[1] / 60);
            }

            // 4. Shelf-life budget over those steps
            double remaining = shelfLifeRemainingPct(temps, hours);
            double peak = temps.isEmpty()
                    ? cargoTempC(pallet, 0) // zero-length trip: departure temperature
                    : temps.stream().mapToDouble(Double::doubleValue).max().getAsDouble();

            results.add(new ScoreResponse.RouteScore(
                    route.routeId(), round(remaining), delayMin, round(totalMin), round(peak)));
        }
        return new ScoreResponse(model.modelVersion(), results);
    }

    // ------------------------------------------------------------------ building blocks

    /** Shelf life in days at a constant temperature: straight lines between the published points. */
    double shelfLifeDaysAt(double tempC) {
        List<V1Model.ShelfLifePoint> pts = model.shelfLife().points();
        // Outside the tested range (0-30 °C), hold the end values
        if (tempC <= pts.get(0).tempC()) return pts.get(0).shelfLifeDays();
        if (tempC >= pts.get(pts.size() - 1).tempC()) return pts.get(pts.size() - 1).shelfLifeDays();
        for (int i = 1; i < pts.size(); i++) {
            V1Model.ShelfLifePoint low = pts.get(i - 1), high = pts.get(i);
            if (tempC <= high.tempC()) {
                double t0 = low.tempC(), d0 = low.shelfLifeDays();
                double t1 = high.tempC(), d1 = high.shelfLifeDays();
                return d0 + (tempC - t0) * (d1 - d0) / (t1 - t0);
            }
        }
        throw new IllegalStateException("unreachable: shelf life points not sorted");
    }

    /** % of shelf life left after the steps (not rounded). Each step uses hours / total life. */
    double shelfLifeRemainingPct(List<Double> stepTempsC, List<Double> stepHours) {
        double used = 0.0;
        for (int i = 0; i < stepTempsC.size(); i++) {
            used += stepHours.get(i) / (shelfLifeDaysAt(stepTempsC.get(i)) * 24);
        }
        return Math.max(0.0, 100 * (1 - used));
    }

    /** Predicted cargo temperature (°C) for a pallet position. */
    double cargoTempC(String pallet, double daysSinceDeparture) {
        V1Model.CargoTemp c = model.cargoTemp();
        return c.baseC() + c.palletOffsetC().get(pallet) + c.driftCPerDay() * daysSinceDeparture;
    }

    /** {startMin, lengthMin} steps covering the trip; the last one may be shorter. */
    List<double[]> splitIntoSteps(double totalMin) {
        int stepMin = model.rules().stepMinutes();
        int fullSteps = (int) Math.floor(totalMin / stepMin);
        List<double[]> steps = new ArrayList<>();
        for (int i = 0; i < fullSteps; i++) steps.add(new double[] {i * stepMin, stepMin});
        double restMin = totalMin - fullSteps * stepMin;
        if (restMin > 0) steps.add(new double[] {fullSteps * stepMin, restMin});
        return steps;
    }

    /** Extra minutes from weather (not rounded). Each stretch uses the worse of its two ends. */
    double routeDelayMin(List<ScoreRequest.Point> points) {
        double total = 0.0;
        for (int i = 1; i < points.size(); i++) {
            ScoreRequest.Point a = points.get(i - 1), b = points.get(i);
            double plannedMin = seconds(a.eta(), b.eta()) / 60;
            V1Model.BucketDelay da = bucket(a), db = bucket(b);
            V1Model.BucketDelay worse = da.speedReduction() >= db.speedReduction() ? da : db;
            total += plannedMin * (worse.timeFactor() - 1);
        }
        return total;
    }

    private V1Model.BucketDelay bucket(ScoreRequest.Point p) {
        return model.delay().buckets().get(p.weatherBucket().jsonName());
    }

    private static double seconds(Instant a, Instant b) {
        return Duration.between(a, b).toNanos() / 1e9;
    }

    /** Round to 1 decimal, half to even on the exact double value (same as Python's round()). */
    private double round(double x) {
        return new BigDecimal(x).setScale(model.rules().outputDecimals(), RoundingMode.HALF_EVEN).doubleValue();
    }

    // ------------------------------------------------------------------ input checks
    // Same rules and messages as reference_scorer.py. Our own pipeline always sends valid
    // input, so hitting one of these means a backend bug.

    private void validateTrip(ScoreRequest req) {
        if (!model.produceType().equals(req.produceType())) {
            throw new IllegalArgumentException("unsupported produce_type '" + req.produceType()
                    + "': v1 supports only '" + model.produceType() + "'");
        }
        if (!model.truckType().equals(req.truckType())) {
            throw new IllegalArgumentException("unsupported truck_type '" + req.truckType()
                    + "': v1 supports only '" + model.truckType() + "'");
        }
        if (req.routes() == null || req.routes().isEmpty()) {
            throw new IllegalArgumentException("routes must be a non-empty list");
        }
    }

    private void validateRoute(ScoreRequest.Route route, int r) {
        if (route.routeId() == null) throw new IllegalArgumentException("missing field 'route_id' in routes[" + r + "]");
        String where = "route '" + route.routeId() + "'";
        if (route.departureTime() == null) throw new IllegalArgumentException("missing field 'departure_time' in " + where);
        if (!(route.googleDurationMin() >= 0)) { // also catches NaN
            throw new IllegalArgumentException(where + ": google_duration_min must not be negative, got "
                    + route.googleDurationMin());
        }
        List<ScoreRequest.Point> pts = route.points();
        if (pts == null || pts.size() < 2) {
            throw new IllegalArgumentException(where + " needs at least 2 points, got " + (pts == null ? 0 : pts.size()));
        }
        for (int p = 0; p < pts.size(); p++) {
            String pointWhere = where + ", point " + p;
            if (pts.get(p).eta() == null) throw new IllegalArgumentException("missing field 'eta' in " + pointWhere);
            if (pts.get(p).weatherBucket() == null) {
                throw new IllegalArgumentException("missing field 'weather_bucket' in " + pointWhere);
            }
            if (p > 0 && !pts.get(p).eta().isAfter(pts.get(p - 1).eta())) {
                throw new IllegalArgumentException(where + ": etas must be in increasing order (point " + p
                        + " is not after point " + (p - 1) + ")");
            }
        }
    }
}
