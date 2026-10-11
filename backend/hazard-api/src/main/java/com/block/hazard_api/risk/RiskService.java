package com.block.hazard_api.risk;

import com.block.hazard_api.hazards.Hazard;
import com.block.hazard_api.routes.SampledPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Loads the v2 risk model once at startup and scores one route's sampled points.
 * If the model files can't be loaded, risk is switched off (assess returns null) and the
 * rest of the backend works exactly as before.
 */
@Service
public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);
    static final String RESOURCE_FOLDER = "/risk_v2/";
    static final int MAX_REASONS = 3;

    /** Risk numbers for one route card (rounded for display). */
    public record RouteRisk(int riskScore, Double incidentRiskRatio, double expectedIncidentDelayMin,
                            double majorSlowdownProbability, List<String> riskFlags, List<String> riskReasons,
                            String riskModelVersion, boolean riskLowConfidence) {}

    private final RiskModelV2 model;

    public RiskService() {
        RiskModelV2 loaded = null;
        try {
            loaded = RiskModelV2.loadFromClasspath(RESOURCE_FOLDER);
            log.info("Loaded risk model {}", loaded.meta().modelVersion());
        } catch (Exception e) {
            log.error("Could not load the risk model; risk fields will be empty", e);
        }
        this.model = loaded;
    }

    /** For tests: use an already-loaded model (or null to switch risk off). */
    public RiskService(RiskModelV2 model) {
        this.model = model;
    }

    /**
     * @param points the route's sampled points with ETAs, in driving order
     * @param alerts active hazards near the route (only NWS alerts with VTEC codes are used)
     * @return the route's risk, or null when the model isn't loaded
     */
    public RouteRisk assess(List<SampledPoint> points, List<Hazard> alerts) {
        if (model == null) return null;
        List<RiskModelV2.PointRisk> pointRisks = new ArrayList<>();
        for (SampledPoint p : points) {
            pointRisks.add(model.point(p.point().lat(), p.point().lng(), p.eta(), alerts));
        }
        RiskModelV2.RouteNumbers n = model.combine(pointRisks);
        Map<String, String> flags = RiskModelV2.flags(pointRisks);
        boolean lowConfidence = RiskModelV2.lowConfidence(pointRisks);
        return new RouteRisk(
                n.riskScore(),
                n.incidentRiskRatio() == null ? null : round(n.incidentRiskRatio(), 2),
                round(n.expectedIncidentDelayMin(), 1),
                round(n.majorSlowdownProbability(), 3),
                List.copyOf(flags.keySet()),
                flags.values().stream().limit(MAX_REASONS).toList(),
                model.meta().modelVersion(),
                lowConfidence);
    }

    private static double round(double x, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(x * scale) / scale;
    }
}
