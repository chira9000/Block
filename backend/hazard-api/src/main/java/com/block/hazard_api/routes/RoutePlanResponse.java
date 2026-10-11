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
            String reason                      // short human-readable explanation
    ) {}

    /** An active hazard (e.g. an NWS warning) that the route goes through. */
    public record HazardCrossed(
            String type,      // e.g. "Winter Storm Warning"
            String headline,
            double severity   // 0.0 (minor) to 1.0 (avoid)
    ) {}
}
