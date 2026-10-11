package com.block.hazard_api.hazards;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class GeoUtilTest {

    // A square near LA: lon -118 to -117, lat 33 to 34. Points are [lon, lat].
    private static final List<List<Double>> SQUARE = List.of(
            List.of(-118.0, 33.0), List.of(-117.0, 33.0),
            List.of(-117.0, 34.0), List.of(-118.0, 34.0),
            List.of(-118.0, 33.0));

    @Test
    void pointInsideSquare() {
        assertTrue(GeoUtil.pointInPolygon(33.5, -117.5, SQUARE));
    }

    @Test
    void pointOutsideSquare() {
        assertFalse(GeoUtil.pointInPolygon(35.0, -117.5, SQUARE));
    }

    @Test
    void nycToPhillyIsAbout130Km() {
        double d = GeoUtil.haversineKm(40.7128, -74.0060, 39.9526, -75.1652);
        assertEquals(130, d, 5); // within 5 km
    }

    @Test
    void squareCenterIsItsMiddle() {
        // SQUARE repeats its first corner at the end; that copy must not pull the center.
        LatLng c = GeoUtil.polygonCenter(SQUARE);
        assertEquals(33.5, c.lat(), 1e-9);
        assertEquals(-117.5, c.lng(), 1e-9);
    }

    @Test
    void squareRadiusIsDistanceToACorner() {
        LatLng c = GeoUtil.polygonCenter(SQUARE);
        double toCorner = GeoUtil.haversineKm(33.5, -117.5, 34.0, -118.0);
        assertEquals(toCorner, GeoUtil.polygonRadiusKm(SQUARE, c), 0.5);
    }

    @Test
    void decodesGooglesExamplePolyline() {
        // Google's documented example. Note the backtick near the end; real polylines can
        // also contain backslashes, which must be written as \\ in a Java string.
        List<LatLng> pts = GeoUtil.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@");
        assertEquals(3, pts.size());
        assertEquals(38.5, pts.get(0).lat(), 1e-6);
        assertEquals(-120.2, pts.get(0).lng(), 1e-6);
        assertEquals(40.7, pts.get(1).lat(), 1e-6);
        assertEquals(-120.95, pts.get(1).lng(), 1e-6);
        assertEquals(43.252, pts.get(2).lat(), 1e-6);
        assertEquals(-126.453, pts.get(2).lng(), 1e-6);
    }

    @Test
    void emptyPolylineGivesNoPoints() {
        assertTrue(GeoUtil.decodePolyline("").isEmpty());
    }

    // Routes are LatLng(lat, lng): latitude first.

    @Test
    void routeThroughSquareEntersAtIndex1() {
        List<LatLng> route = List.of(
                new LatLng(33.5, -119.0),  // west of the square
                new LatLng(33.5, -117.5),  // inside
                new LatLng(33.5, -116.0)); // east of the square
        assertEquals(1, GeoUtil.firstCrossingIndex(route, SQUARE));
        assertTrue(GeoUtil.routeCrossesPolygon(route, SQUARE));
    }

    @Test
    void routeAcrossSquareWithNoPointsInsideStillCrosses() {
        List<LatLng> route = List.of(
                new LatLng(33.5, -119.0),  // west of the square
                new LatLng(33.5, -116.0)); // east of the square, so the road goes right through it
        assertEquals(1, GeoUtil.firstCrossingIndex(route, SQUARE));
        assertTrue(GeoUtil.routeCrossesPolygon(route, SQUARE));
    }

    @Test
    void routeNorthOfSquareDoesNotCross() {
        List<LatLng> route = List.of(
                new LatLng(35.0, -119.0),
                new LatLng(35.0, -116.0));
        assertEquals(-1, GeoUtil.firstCrossingIndex(route, SQUARE));
        assertFalse(GeoUtil.routeCrossesPolygon(route, SQUARE));
    }

    @Test
    void segmentsIntersectSideOfLineTest() {
        // An X shape crosses; two parallel lines don't.
        assertTrue(GeoUtil.segmentsIntersect(0, 0, 2, 2, 0, 2, 2, 0));
        assertFalse(GeoUtil.segmentsIntersect(0, 0, 2, 0, 0, 1, 2, 1));
        // Same infinite line but far apart: must NOT count as crossing.
        assertFalse(GeoUtil.segmentsIntersect(0, 0, 1, 0, 2, 0, 3, 0));
    }
}
