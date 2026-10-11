package com.block.hazard_api.routes;

import java.util.List;

/** What POST /routes/plan returns to the app: routes ranked best first. */
public record RoutePlanResponse(
        String modelVersion,
        List<PlannedRoute> routes
) {

    /** One route card. Distances in km, durations in minutes, temperatures in °C. */
    public record PlannedRoute(
            int rank,                          // 1 = best (most shelf life left)
            String routeId,
            String encodedPolyline,            // Google format; the app draws it on the map
            double distanceKm,
            double googleDurationMin,
            double predictedTotalDurationMin,  // Google estimate + predicted weather delay
            double shelfLifeRemainingPct,      // 0-100, the ranking value
            double peakCargoTempC,
            List<HazardCrossed> hazardsCrossed,
            String reason,                     // short human-readable explanation
            // v2 risk model; all null when the risk part is unavailable
            Integer riskScore,                 // % chance of meeting at least one delaying incident
            Double incidentRiskRatio,          // incident risk vs normal for these roads (1.0 = normal)
            Double expectedIncidentDelayMin,   // already included in predictedTotalDurationMin
            Double majorSlowdownProbability,   // chance of at least one major slowdown, 0-1
            List<String> riskFlags,            // e.g. "winter_closure_or_chains"
            List<String> riskReasons,          // up to 3 one-line reasons
            String riskModelVersion,
            Boolean riskLowConfidence          // true if more than half the points are on roads the model hasn't seen
    ) {
        /** Route card without risk fields. */
        public PlannedRoute(int rank, String routeId, String encodedPolyline, double distanceKm,
                            double googleDurationMin, double predictedTotalDurationMin,
                            double shelfLifeRemainingPct, double peakCargoTempC,
                            List<HazardCrossed> hazardsCrossed, String reason) {
            this(rank, routeId, encodedPolyline, distanceKm, googleDurationMin, predictedTotalDurationMin,
                    shelfLifeRemainingPct, peakCargoTempC, hazardsCrossed, reason,
                    null, null, null, null, null, null, null, null);
        }
    }

    /** An active hazard (e.g. an NWS warning) that the route goes through. */
    public record HazardCrossed(
            String type,      // e.g. "Winter Storm Warning"
            String headline,
            double severity   // 0.0 (minor) to 1.0 (avoid)
    ) {}
}
