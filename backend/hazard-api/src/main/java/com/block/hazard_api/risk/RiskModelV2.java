package com.block.hazard_api.risk;

import com.block.hazard_api.hazards.GeoUtil;
import com.block.hazard_api.hazards.Hazard;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.uber.h3core.H3Core;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The v2 route risk model (model/export/risk_v2): per-point incident probability from two
 * LightGBM models, combined into route numbers. Every setting comes from risk_model_meta.json.
 */
public final class RiskModelV2 {

    // Route formula constants
    static final double ENCOUNTERS_PER_100KM = 0.3;       // ASSUMPTION: delaying incidents a truck meets per
                                                          // 100 km on an average road at an average time
    static final double MINOR_INCIDENT_DELAY_MIN = 10.0;  // ASSUMPTION: truck delay from a minor incident
    static final double MAJOR_INCIDENT_DELAY_MIN = 30.0;  // ASSUMPTION: truck delay from a major slowdown
    static final double MAX_INCIDENT_DELAY_MIN = 120.0;   // cap on a route's expected incident delay
    static final double RISK_SCORE_SCALE_MIN = 30.0;      // 30 min expected delay -> risk score 63

    /** The parts of risk_model_meta.json the backend needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(
            @JsonProperty("model_version") String modelVersion,
            @JsonProperty("h3_resolution") int h3Resolution,
            @JsonProperty("features") List<String> features,
            @JsonProperty("calibration") Platt calibration,
            @JsonProperty("major_calibration") Platt majorCalibration,
            @JsonProperty("unknown_cell_log_rate") double unknownCellLogRate,
            @JsonProperty("nws_groups") Map<String, List<String>> nwsGroups,
            @JsonProperty("nws_level") Map<String, Integer> nwsLevel,
            @JsonProperty("time_features_timezone") String timeFeaturesTimezone,
            @JsonProperty("route_formula") RouteFormula routeFormula,
            @JsonProperty("overall_mean_p_2021") double overallMeanP2021,
            @JsonProperty("avg_incident_duration_min_2021") double avgIncidentDurationMin2021
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Platt(@JsonProperty("a") double a, @JsonProperty("b") double b) {
        /** p = 1 / (1 + exp(-(a * logit(p_raw) + b))) */
        double apply(double pRaw) {
            double p = Math.min(Math.max(pRaw, 1e-15), 1 - 1e-15);
            double logit = Math.log(p / (1 - p));
            return 1.0 / (1.0 + Math.exp(-(a * logit + b)));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RouteFormula(@JsonProperty("cap_low") double capLow, @JsonProperty("cap_high") double capHigh) {}

    /** One row of road_cells_features.csv. */
    record Cell(double logCellRate, int isMotorway, int roadPoints, double placeRate2021, double cellMeanP) {}

    /** Everything known about one route point. */
    public record PointRisk(double lat, double lon, String h3Cell, boolean knownCell, double[] features,
                            double incidentProbability, double majorProbability, double placeRate2021,
                            double cellMeanP, double pModel, double pIncident, double majorShare,
                            List<Hazard> activeAlerts) {}

    /** Route-level numbers before rounding. */
    public record RouteNumbers(double expectedIncidentDelayMin, double majorSlowdownProbability,
                               Double incidentRiskRatio, int riskScore) {}

    private final Meta meta;
    private final Map<String, Cell> cells;
    private final double minPlaceRate;
    private final LightGbmModel incident;
    private final LightGbmModel major;
    private final H3Core h3;
    private final ZoneId zone;

    private RiskModelV2(Meta meta, Map<String, Cell> cells, LightGbmModel incident, LightGbmModel major)
            throws IOException {
        this.meta = meta;
        this.cells = cells;
        this.minPlaceRate = cells.values().stream().mapToDouble(Cell::placeRate2021).min().orElseThrow();
        this.incident = incident;
        this.major = major;
        this.h3 = H3Core.newInstance();
        this.zone = ZoneId.of(meta.timeFeaturesTimezone());
    }

    /** Loads the model from a classpath folder, e.g. "/risk_v2/". */
    public static RiskModelV2 loadFromClasspath(String folder) throws IOException {
        Meta meta;
        try (InputStream in = open(folder + "risk_model_meta.json")) {
            meta = JsonMapper.builder().build().readValue(in, Meta.class);
        }
        LightGbmModel incident, major;
        try (InputStream in = open(folder + "incident_model.txt")) {
            incident = LightGbmModel.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        try (InputStream in = open(folder + "major_model.txt")) {
            major = LightGbmModel.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Map<String, Cell> cells;
        try (InputStream in = open(folder + "road_cells_features.csv")) {
            cells = readCells(in);
        }
        return new RiskModelV2(meta, cells, incident, major);
    }

    public Meta meta() {
        return meta;
    }

    public LightGbmModel incidentModel() {
        return incident;
    }

    public LightGbmModel majorModel() {
        return major;
    }

    // ------------------------------------------------------------------ features (one point)

    /** Features, model outputs and calibrated probabilities for one route point. */
    public PointRisk point(double lat, double lon, Instant eta, List<Hazard> alerts) {
        String cellId = h3.latLngToCellAddress(lat, lon, meta.h3Resolution());
        Cell cell = cells.get(cellId);
        boolean known = cell != null;
        if (!known) {
            // Unknown cell: meta's log rate, no motorway, 1 road point, lowest place rate, overall mean p
            cell = new Cell(meta.unknownCellLogRate(), 0, 1, minPlaceRate, meta.overallMeanP2021());
        }

        List<Hazard> active = activeCovering(alerts, lat, lon, eta);
        Map<String, Double> values = new HashMap<>();
        for (Map.Entry<String, List<String>> group : meta.nwsGroups().entrySet()) {
            boolean hit = active.stream().anyMatch(a -> group.getValue().contains(a.vtecPhenom()));
            values.put(group.getKey(), hit ? 1.0 : 0.0);
        }
        int maxLevel = 0;
        for (Hazard a : active) {
            maxLevel = Math.max(maxLevel, meta.nwsLevel().getOrDefault(a.vtecSig(), 0));
        }
        values.put("nws_max_level", (double) maxLevel);
        values.put("nws_active_count", (double) active.size());
        values.put("fire_active", 0.0);      // no fire feed yet
        values.put("log_fire_acres", 0.0);
        values.put("storm_flood", 0.0);      // no storm-report feed yet
        values.put("storm_any", 0.0);
        values.put("is_motorway", (double) cell.isMotorway());
        values.put("road_points", (double) cell.roadPoints());
        values.put("log_cell_rate", cell.logCellRate());
        ZonedDateTime local = eta.atZone(zone);
        values.put("local_hour", (double) local.getHour());
        values.put("day_of_week", (double) (local.getDayOfWeek().getValue() - 1)); // Monday = 0
        values.put("month", (double) local.getMonthValue());

        double[] x = new double[meta.features().size()];
        for (int i = 0; i < x.length; i++) {
            Double v = values.get(meta.features().get(i));
            if (v == null) throw new IllegalStateException("no value for model feature " + meta.features().get(i));
            x[i] = v;
        }

        double pIncidentRaw = incident.predictProbability(x);
        double pMajorRaw = major.predictProbability(x);
        double pModel = meta.calibration().apply(pIncidentRaw);
        double multiplier = Math.min(Math.max(pModel / cell.cellMeanP(), meta.routeFormula().capLow()),
                meta.routeFormula().capHigh());
        double pIncident = cell.placeRate2021() * multiplier;
        double majorShare = meta.majorCalibration().apply(pMajorRaw);
        return new PointRisk(lat, lon, cellId, known, x, pIncidentRaw, pMajorRaw, cell.placeRate2021(),
                cell.cellMeanP(), pModel, pIncident, majorShare, active);
    }

    /**
     * NWS alerts with a VTEC code that cover the point and are active at the ETA
     * (startsAt <= eta < expiresAt; a missing time means open-ended).
     */
    static List<Hazard> activeCovering(List<Hazard> alerts, double lat, double lon, Instant eta) {
        List<Hazard> out = new ArrayList<>();
        for (Hazard a : alerts) {
            if (a.vtecPhenom() == null) continue;
            if (a.startsAt() != null && eta.isBefore(a.startsAt())) continue;
            if (a.expiresAt() != null && !eta.isBefore(a.expiresAt())) continue;
            boolean covers = a.polygon() != null && a.polygon().size() >= 3
                    ? GeoUtil.pointInPolygon(lat, lon, a.polygon())
                    : GeoUtil.haversineKm(lat, lon, a.lat(), a.lon()) <= a.radiusKm();
            if (covers) out.add(a);
        }
        return out;
    }

    // ------------------------------------------------------------------ route formula

    /**
     * Combines route points in driving order; each point covers the segment to the next point.
     * e_i = expected delaying incidents met on that segment
     *     = ENCOUNTERS_PER_100KM * (km / 100) * (p_incident / network-average p).
     */
    public RouteNumbers combine(List<PointRisk> points) {
        double incidentKm = 0, baseKm = 0, delay = 0, noMajor = 1;
        for (int i = 0; i + 1 < points.size(); i++) {
            PointRisk a = points.get(i), b = points.get(i + 1);
            double km = GeoUtil.haversineKm(a.lat(), a.lon(), b.lat(), b.lon());
            double relNetwork = a.pIncident() / meta.overallMeanP2021();
            double e = ENCOUNTERS_PER_100KM * (km / 100) * relNetwork;
            double ms = a.majorShare();
            incidentKm += a.pIncident() * km;
            baseKm += a.placeRate2021() * km;
            delay += e * (ms * MAJOR_INCIDENT_DELAY_MIN + (1 - ms) * MINOR_INCIDENT_DELAY_MIN);
            noMajor *= 1 - Math.min(1.0, e * ms);
        }
        delay = Math.min(delay, MAX_INCIDENT_DELAY_MIN);
        Double ratio = baseKm > 0 ? incidentKm / baseKm : null;
        int score = (int) Math.round(100 * (1 - Math.exp(-delay / RISK_SCORE_SCALE_MIN)));
        return new RouteNumbers(delay, 1 - noMajor, ratio, score);
    }

    /** Low confidence when MORE THAN HALF of the points are on road cells the model hasn't seen. */
    static boolean lowConfidence(List<PointRisk> points) {
        long unknown = points.stream().filter(p -> !p.knownCell()).count();
        return unknown * 2 > points.size();
    }

    /**
     * Closure-type flags from warnings along the route, in order of first appearance,
     * each with a one-line reason (keyed by flag).
     */
    static Map<String, String> flags(List<PointRisk> points) {
        Map<String, String> out = new LinkedHashMap<>();
        for (PointRisk p : points) {
            for (Hazard a : p.activeAlerts()) {
                String flag = flagFor(a.vtecPhenom(), a.vtecSig());
                if (flag == null || out.containsKey(flag)) continue;
                String event = a.type() != null ? a.type() : a.vtecPhenom() + "." + a.vtecSig();
                out.put(flag, switch (flag) {
                    case "winter_closure_or_chains" -> event + " on the route: chain controls or closures possible";
                    case "flood_closure_risk" -> event + " on the route: flooded roads may be closed";
                    default -> event + " on the route: dangerous for tall, high-profile trucks";
                });
            }
        }
        return out;
    }

    static String flagFor(String phenom, String sig) {
        if (!"W".equals(sig) || phenom == null) return null;
        return switch (phenom) {
            case "WS", "BZ", "IS" -> "winter_closure_or_chains";
            case "FF", "FL", "FA" -> "flood_closure_risk";
            case "HW", "EW" -> "high_wind_tall_vehicles";
            default -> null;
        };
    }

    // ------------------------------------------------------------------ loading helpers

    private static InputStream open(String resource) throws IOException {
        InputStream in = RiskModelV2.class.getResourceAsStream(resource);
        if (in == null) throw new IOException("missing resource " + resource);
        return in;
    }

    private static Map<String, Cell> readCells(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<String> header = List.of(reader.readLine().strip().split(","));
        int id = header.indexOf("h3_cell"), logRate = header.indexOf("log_cell_rate"),
                motorway = header.indexOf("is_motorway"), roadPoints = header.indexOf("road_points"),
                placeRate = header.indexOf("place_rate_2021"), meanP = header.indexOf("cell_mean_p");
        if (Math.min(Math.min(id, logRate), Math.min(Math.min(motorway, roadPoints), Math.min(placeRate, meanP))) < 0) {
            throw new IOException("road_cells_features.csv is missing a column: " + header);
        }
        Map<String, Cell> cells = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            String[] f = line.strip().split(",");
            cells.put(f[id], new Cell(Double.parseDouble(f[logRate]), Integer.parseInt(f[motorway]),
                    Integer.parseInt(f[roadPoints]), Double.parseDouble(f[placeRate]), Double.parseDouble(f[meanP])));
        }
        return cells;
    }
}
