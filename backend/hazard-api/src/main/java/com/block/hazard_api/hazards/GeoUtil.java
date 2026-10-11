package com.block.hazard_api.hazards;
import java.util.ArrayList;
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

    /**
     * Center of a polygon = average of its points. Polygon points are [lon, lat].
     * GeoJSON rings repeat the first point at the end; we skip that copy so the
     * first corner isn't counted twice.
     */
    public static LatLng polygonCenter(List<List<Double>> polygon) {
        int n = polygon.size();
        if (n > 1 && polygon.get(0).equals(polygon.get(n - 1))) n--; // drop the closing point
        double lat = 0, lon = 0;
        for (int i = 0; i < n; i++) {
            lon += polygon.get(i).get(0);
            lat += polygon.get(i).get(1);
        }
        return new LatLng(lat / n, lon / n);
    }

    /** Radius in km = distance from the center to the farthest polygon point. */
    public static double polygonRadiusKm(List<List<Double>> polygon, LatLng center) {
        double radiusKm = 0;
        for (List<Double> pt : polygon) {
            radiusKm = Math.max(radiusKm, haversineKm(center.lat(), center.lng(), pt.get(1), pt.get(0)));
        }
        return radiusKm;
    }

    /**
     * Turns a Google encoded polyline (what the Routes API returns) into route points.
     * Format: https://developers.google.com/maps/documentation/utilities/polylinealgorithm
     * Each value is the CHANGE from the previous point, times 1e5, packed into 5-bit chunks.
     */
    public static List<LatLng> decodePolyline(String encoded) {
        List<LatLng> points = new ArrayList<>();
        int[] index = {0}; // an array so readValue can move it forward
        int lat = 0, lng = 0;
        while (index[0] < encoded.length()) {
            lat += readValue(encoded, index);
            lng += readValue(encoded, index);
            points.add(new LatLng(lat / 1e5, lng / 1e5));
        }
        return points;
    }

    /** Reads one number from the polyline, starting at index[0]. */
    private static int readValue(String encoded, int[] index) {
        int result = 0, shift = 0, chunk;
        do {
            chunk = encoded.charAt(index[0]++) - 63;  // each char was shifted by 63 to be printable
            result |= (chunk & 0x1f) << shift;        // keep the low 5 bits
            shift += 5;
        } while (chunk >= 0x20);                      // 0x20 bit set = more chunks follow
        // Lowest bit says "negative"; undo that encoding
        return (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
    }

    /**
     * Index of the first route point where the route enters the polygon, or -1 if it never does.
     * A point counts if it is inside the polygon, OR if the road from the previous point
     * to it crosses the polygon's edge (so we don't miss thin polygons between two points).
     */
    public static int firstCrossingIndex(List<LatLng> route, List<List<Double>> polygon) {
        for (int i = 0; i < route.size(); i++) {
            LatLng p = route.get(i);
            if (pointInPolygon(p.lat(), p.lng(), polygon)) return i;
            if (i > 0 && segmentCrossesPolygon(route.get(i - 1), p, polygon)) return i;
        }
        return -1;
    }

    public static boolean routeCrossesPolygon(List<LatLng> route, List<List<Double>> polygon) {
        return firstCrossingIndex(route, polygon) >= 0;
    }

    /** True if the straight line a→b crosses any edge of the polygon. */
    private static boolean segmentCrossesPolygon(LatLng a, LatLng b, List<List<Double>> polygon) {
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i).get(0), yi = polygon.get(i).get(1);
            double xj = polygon.get(j).get(0), yj = polygon.get(j).get(1);
            // x = longitude, y = latitude, so the route points go in as (lng, lat)
            if (segmentsIntersect(a.lng(), a.lat(), b.lng(), b.lat(), xj, yj, xi, yi)) return true;
        }
        return false;
    }

    /**
     * True if segment P1-P2 crosses segment P3-P4 (flat x/y math; fine at the scale of an alert).
     * Two segments cross when each one's endpoints sit on opposite sides of the other one.
     * Package-private so the tests can call it.
     */
    static boolean segmentsIntersect(double x1, double y1, double x2, double y2,
                                     double x3, double y3, double x4, double y4) {
        double d1 = side(x3, y3, x4, y4, x1, y1);
        double d2 = side(x3, y3, x4, y4, x2, y2);
        double d3 = side(x1, y1, x2, y2, x3, y3);
        double d4 = side(x1, y1, x2, y2, x4, y4);
        // Opposite signs means "opposite sides". A 0 means touching, which we also count.
        return d1 * d2 <= 0 && d3 * d4 <= 0
                && boxesOverlap(x1, y1, x2, y2, x3, y3, x4, y4); // rules out far-apart lines on the same infinite line
    }

    /** Positive if (px, py) is left of the line A→B, negative if right, 0 if on it. */
    private static double side(double ax, double ay, double bx, double by, double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    private static boolean boxesOverlap(double x1, double y1, double x2, double y2,
                                        double x3, double y3, double x4, double y4) {
        return Math.max(x1, x2) >= Math.min(x3, x4) && Math.max(x3, x4) >= Math.min(x1, x2)
                && Math.max(y1, y2) >= Math.min(y3, y4) && Math.max(y3, y4) >= Math.min(y1, y2);
    }
}