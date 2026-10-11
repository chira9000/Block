package com.block.hazard_api.hazards;

/** The map area the app is looking at. Used to filter /hazards. */
public record BoundingBox(double minLat, double maxLat, double minLon, double maxLon) {

    /** True if a hazard circle (center + radius) overlaps this box. Approximate, good enough for a map. */
    public boolean overlaps(double lat, double lon, double radiusKm) {
        double marginDeg = radiusKm / 111.0; // ~111 km per degree
        return lat >= minLat - marginDeg && lat <= maxLat + marginDeg
                && lon >= minLon - marginDeg && lon <= maxLon + marginDeg;
    }

    /**
     * Reads a bbox string "minLon,minLat,maxLon,maxLat" (west, south, east, north).
     * That's the standard GeoJSON order: longitude first, like our polygons.
     * Throws IllegalArgumentException with a readable message if it's malformed.
     */
    public static BoundingBox parse(String bbox) {
        String[] parts = bbox.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("bbox must be 4 numbers: minLon,minLat,maxLon,maxLat");
        }
        double[] v = new double[4];
        for (int i = 0; i < 4; i++) {
            try {
                v[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bbox value '" + parts[i].trim() + "' is not a number");
            }
        }
        double minLon = v[0], minLat = v[1], maxLon = v[2], maxLat = v[3];
        if (minLat < -90 || maxLat > 90) throw new IllegalArgumentException("bbox latitudes must be between -90 and 90");
        if (minLon < -180 || maxLon > 180) throw new IllegalArgumentException("bbox longitudes must be between -180 and 180");
        if (minLat > maxLat || minLon > maxLon) {
            throw new IllegalArgumentException("bbox must be minLon,minLat,maxLon,maxLat (min before max)");
        }
        return new BoundingBox(minLat, maxLat, minLon, maxLon);
    }
}
