package com.block.hazard_api.routes;

import com.block.hazard_api.hazards.LatLng;
import java.util.List;

/**
 * One piece of a route (a Routes API "step", e.g. "merge onto I-5 N"):
 * its decoded polyline points and how long Google says it takes.
 */
public record RouteStep(List<LatLng> points, double durationSeconds) {}
