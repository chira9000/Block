package com.block.hazard_api.routes;

import java.time.Instant;

/**
 * Body of POST /routes/plan, sent by the app.
 *
 * origin/destination: addresses as typed, e.g. "Fresno, CA"
 * departureTime:      ISO 8601 UTC, e.g. "2026-10-12T08:00:00Z" (optional: now if left out)
 * produceType:        only "strawberries" for now
 */
public record RoutePlanRequest(
        String origin,
        String destination,
        Instant departureTime,
        String produceType
) {}
