package com.block.hazard_api.routes;

import java.util.List;

/**
 * One route Google suggested, ready for sampling.
 *
 * durationSeconds: Google's traffic-aware estimate for the whole route
 * steps:           the route's pieces; their durations add up to durationSeconds
 */
public record CandidateRoute(
        String encodedPolyline,
        double distanceKm,
        double durationSeconds,
        List<RouteStep> steps
) {}
