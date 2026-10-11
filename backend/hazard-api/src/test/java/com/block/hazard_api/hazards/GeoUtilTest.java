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
}
