package com.block.hazard_api.hazards;
import java.util.List;
/** Small geometry helpers used across the backend. */
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

    /** True if (lat, lon) is inside the polygon. Polygon points are [lon, lat]. */
    public static boolean pointInPolygon(double lat, double lon, List<List<Double>> polygon) {
        boolean inside = false;
        int n = polygon.size();
        // Walk each edge: from point j to point i
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i).get(0), yi = polygon.get(i).get(1);
            double xj = polygon.get(j).get(0), yj = polygon.get(j).get(1);
            // Does this edge straddle our latitude, and is the crossing to the right of us?
            boolean crosses = (yi > lat) != (yj > lat)
                    && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi;
            if (crosses) inside = !inside; // each crossing flips inside/outside
        }
        return inside;
    }
}