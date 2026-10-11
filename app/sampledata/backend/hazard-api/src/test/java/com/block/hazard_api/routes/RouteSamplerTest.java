package com.block.hazard_api.routes;

import static org.junit.jupiter.api.Assertions.*;

import com.block.hazard_api.hazards.GeoUtil;
import com.block.hazard_api.hazards.LatLng;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteSamplerTest {

    private static final Instant DEPART = Instant.parse("2026-10-12T08:00:00Z");

    /** Points due north along longitude -118, from lat1 to lat2, every 0.01° (~1.1 km). */
    private static List<LatLng> north(double lat1, double lat2) {
        List<LatLng> pts = new ArrayList<>();
        int n = (int) Math.round((lat2 - lat1) / 0.01);
        for (int i = 0; i <= n; i++) pts.add(new LatLng(lat1 + i * 0.01, -118.0));
        return pts;
    }

    private static double km(LatLng a, LatLng b) {
        return GeoUtil.haversineKm(a.lat(), a.lng(), b.lat(), b.lng());
    }

    @Test
    void startsAtOriginAndEndsAtDestination() {
        List<RouteStep> steps = List.of(new RouteStep(north(34.0, 35.0), 3600)); // ~111 km in 1 h
        List<SampledPoint> s = RouteSampler.sample(steps, DEPART);

        assertEquals(new LatLng(34.0, -118.0), s.get(0).point());
        assertEquals(DEPART, s.get(0).eta());
        assertEquals(35.0, s.get(s.size() - 1).point().lat(), 1e-9);
        // Last ETA = departure + route duration
        assertEquals(DEPART.plusSeconds(3600), s.get(s.size() - 1).eta());
    }

    @Test
    void pointsAreAbout24KmApart() {
        List<SampledPoint> s = RouteSampler.sample(List.of(new RouteStep(north(34.0, 35.0), 3600)), DEPART);
        // 111 km: samples at 0, 24, 48, 72, 96 km, then the destination at ~111 km
        assertEquals(6, s.size());
        for (int i = 1; i < s.size() - 1; i++) {
            assertEquals(24.0, km(s.get(i - 1).point(), s.get(i).point()), 0.1);
        }
    }

    @Test
    void etasStrictlyIncrease() {
        List<RouteStep> steps = List.of(
                new RouteStep(north(34.0, 34.5), 1800),
                new RouteStep(List.of(new LatLng(34.5, -118.0)), 600), // no distance, 10 min stop
                new RouteStep(north(34.5, 35.5), 4000));
        List<SampledPoint> s = RouteSampler.sample(steps, DEPART);
        for (int i = 1; i < s.size(); i++) {
            assertTrue(s.get(i).eta().isAfter(s.get(i - 1).eta()), "ETA " + i + " should be after ETA " + (i - 1));
        }
        assertEquals(DEPART.plusSeconds(1800 + 600 + 4000), s.get(s.size() - 1).eta());
    }

    @Test
    void slowStepGetsMoreTimePerKm() {
        // Step 1: 0.5° (~55.6 km) in 1 h. Step 2: next 0.5° in 2 h (half the speed).
        List<RouteStep> steps = List.of(
                new RouteStep(north(34.0, 34.5), 3600),
                new RouteStep(north(34.5, 35.0), 7200));
        List<SampledPoint> s = RouteSampler.sample(steps, DEPART);
        double stepKm = km(new LatLng(34.0, -118.0), new LatLng(34.5, -118.0));

        // Sample at 24 km is in step 1
        assertEquals(24.0 / stepKm * 3600, secondsAfterDeparture(s.get(1)), 2);
        // Sample at 72 km is in step 2: 1 h + its share of step 2's 2 h
        assertEquals(3600 + (72.0 - stepKm) / stepKm * 7200, secondsAfterDeparture(s.get(3)), 2);
    }

    @Test
    void shortRouteIsJustOriginAndDestination() {
        List<SampledPoint> s = RouteSampler.sample(List.of(new RouteStep(north(34.0, 34.1), 900)), DEPART);
        assertEquals(2, s.size());
        assertEquals(DEPART.plusSeconds(900), s.get(1).eta());
    }

    @Test
    void emptyRouteGivesNoPoints() {
        assertTrue(RouteSampler.sample(List.of(), DEPART).isEmpty());
    }

    @Test
    void worksOnADecodedPolyline() {
        List<LatLng> pts = GeoUtil.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@");
        List<SampledPoint> s = RouteSampler.sample(List.of(new RouteStep(pts, 10 * 3600)), DEPART);
        assertEquals(pts.get(0), s.get(0).point());
        assertEquals(pts.get(pts.size() - 1), s.get(s.size() - 1).point());
        assertEquals(DEPART.plus(Duration.ofHours(10)), s.get(s.size() - 1).eta());
    }

    private static double secondsAfterDeparture(SampledPoint p) {
        return Duration.between(DEPART, p.eta()).toMillis() / 1000.0;
    }
}
