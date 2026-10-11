package com.block.hazard_api.hazards;

/**
 * One point on a route. Latitude FIRST (unlike polygons, which are [lon, lat]).
 * The field is called "lng" to match the API contract.
 */
public record LatLng(double lat, double lng) {}
