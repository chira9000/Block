package com.block.hazard_api.routes;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RoutesApiClientTest {

    /**
     * Two routes in the documented computeRoutes response format (Fresno -> Sacramento).
     * Hand-made, not a real Google response: swap in a saved real one once we have it.
     */
    static List<CandidateRoute> loadExample() throws Exception {
        try (InputStream in = RoutesApiClientTest.class.getResourceAsStream("/google/routes-response-example.json")) {
            var body = JsonMapper.builder().build().readValue(in, RoutesApiClient.RoutesResponse.class);
            return RoutesApiClient.toCandidates(body);
        }
    }

    @Test
    void parsesEveryRouteInOrder() throws Exception {
        List<CandidateRoute> routes = loadExample();
        assertEquals(2, routes.size());
        CandidateRoute first = routes.get(0);
        assertEquals(276.0, first.distanceKm(), 1e-9);
        assertEquals(10200.0, first.durationSeconds(), 1e-9);
        assertFalse(first.encodedPolyline().isEmpty());
        assertEquals(2, first.steps().size());
        // First step starts in Fresno
        assertEquals(36.7378, first.steps().get(0).points().get(0).lat(), 1e-5);
    }

    @Test
    void stepTimesAreScaledToTheTrafficAwareTotal() throws Exception {
        // Steps say 5400 s + 4200 s = 9600 s, but with traffic the route takes 10200 s.
        CandidateRoute first = loadExample().get(0);
        double sum = first.steps().stream().mapToDouble(RouteStep::durationSeconds).sum();
        assertEquals(10200.0, sum, 1e-6);
        assertEquals(5400.0 * 10200 / 9600, first.steps().get(0).durationSeconds(), 1e-6);
    }

    @Test
    void sampledEtasEndAtDeparturePlusGoogleDuration() throws Exception {
        Instant depart = Instant.parse("2026-10-12T08:00:00Z");
        CandidateRoute first = loadExample().get(0);
        List<SampledPoint> s = RouteSampler.sample(first.steps(), depart);
        assertEquals(depart.plusSeconds(10200), s.get(s.size() - 1).eta());
    }

    @Test
    void requestAsksForAlternativesAtTheDepartureTime() {
        Map<String, Object> body = RoutesApiClient.requestBody("Fresno, CA", "Sacramento, CA",
                Instant.parse("2026-10-12T08:00:00Z"));
        assertEquals(Map.of("address", "Fresno, CA"), body.get("origin"));
        assertEquals(Map.of("address", "Sacramento, CA"), body.get("destination"));
        assertEquals("DRIVE", body.get("travelMode"));
        assertEquals("TRAFFIC_AWARE", body.get("routingPreference"));
        assertEquals("2026-10-12T08:00:00Z", body.get("departureTime"));
        assertEquals(true, body.get("computeAlternativeRoutes"));
    }

    @Test
    void fieldMaskIncludesStepDurations() {
        assertTrue(RoutesApiClient.FIELD_MASK.contains("routes.legs.steps.staticDuration"));
        assertTrue(RoutesApiClient.FIELD_MASK.contains("routes.duration"));
        assertTrue(RoutesApiClient.FIELD_MASK.contains("routes.polyline.encodedPolyline"));
    }

    @Test
    void parsesGoogleDurations() {
        assertEquals(1234.0, RoutesApiClient.parseSeconds("1234s"));
        assertEquals(0.0, RoutesApiClient.parseSeconds(null));
        assertEquals(0.0, RoutesApiClient.parseSeconds("abc"));
    }

    @Test
    void missingKeyFailsClearlyWithoutCallingGoogle() {
        var client = new RoutesApiClient("");
        assertThrows(RoutesApiClient.MissingApiKeyException.class,
                () -> client.computeRoutes("A", "B", Instant.now()));
    }
}
