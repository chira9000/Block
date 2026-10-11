package com.block.hazard_api.hazards;

/** Small geometry helpers. The route scorer in hours 5-7 will reuse these. */
public final class GeoUtil {
    private static final double EARTH_RADIUS_KM = 6371.0;

    private GeoUtil() {}

    /** Straight-line distance in km between two lat/lon points. */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }

    public static double clamp01(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }
}