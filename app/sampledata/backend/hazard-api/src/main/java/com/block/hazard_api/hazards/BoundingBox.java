package com.block.hazard_api.hazards;

/** The map area the app is looking at. Used to filter /hazards. */
public record BoundingBox(double minLat, double maxLat, double minLon, double maxLon) {

    /** True if a hazard circle (center + radius) overlaps this box. Approximate, good enough for a map. */
    public boolean overlaps(double lat, double lon, double radiusKm) {
        double marginDeg = radiusKm / 111.0; // ~111 km per degree
        return lat >= minLat - marginDeg && lat <= maxLat + marginDeg
                && lon >= minLon - marginDeg && lon <= maxLon + marginDeg;
    }
}
