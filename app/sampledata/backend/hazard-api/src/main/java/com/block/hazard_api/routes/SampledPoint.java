package com.block.hazard_api.routes;

import com.block.hazard_api.hazards.LatLng;
import java.time.Instant;

/** A point along the route plus when the truck is expected to get there (UTC). */
public record SampledPoint(LatLng point, Instant eta) {}
