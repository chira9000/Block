package com.block.hazard_api.routes;

import static org.junit.jupiter.api.Assertions.*;

import com.block.hazard_api.hazards.LatLng;
import com.block.hazard_api.scoring.CoefficientRouteScorer;
import com.block.hazard_api.scoring.RouteScorer;
import com.block.hazard_api.scoring.ScoreRequest;
import com.block.hazard_api.scoring.StubRouteScorer;
import com.block.hazard_api.weather.KeywordWeatherBucketMapper;
import com.block.hazard_api.weather.NwsForecastClient;
import com.block.hazard_api.weather.PointForecast;
import com.block.hazard_api.weather.WeatherBucket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Runs the whole pipeline with fake Google and NWS clients, so no network is needed. */
class RoutePlanServiceTest {

    private static final Instant DEPART = Instant.parse("2099-10-12T08:00:00Z"); // safely in the future

    /** Fake Google: returns the saved example routes and remembers what it was asked. */
    static class FakeRoutes extends RoutesApiClient {
        final List<Instant> departuresAsked = new ArrayList<>();

        FakeRoutes() {
            super("test-key");
        }

        @Override
        public List<CandidateRoute> computeRoutes(String origin, String destination, Instant departure) {
            departuresAsked.add(departure);
            try {
                return RoutesApiClientTest.loadExample();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Fake NWS: "Rain Likely" at 70% wherever hasForecast says yes, nothing elsewhere. */
    static class FakeForecasts extends NwsForecastClient {
        private final Predicate<LatLng> hasForecast;

        FakeForecasts(Predicate<LatLng> hasForecast) {
            this.hasForecast = hasForecast;
        }

        @Override
        public Optional<PointForecast> forecastAt(LatLng point, Instant eta) {
            if (!hasForecast.test(point)) return Optional.empty();
            return Optional.of(new PointForecast(eta, eta.plusSeconds(3600), 15.0, 20.0, 70, "Rain Likely"));
        }
    }

    private static RoutePlanService service(FakeRoutes routes, NwsForecastClient nws, RouteScorer scorer) {
        return new RoutePlanService(routes, nws, new KeywordWeatherBucketMapper(), scorer);
    }

    private static RoutePlanRequest request(String produce, Instant departure) {
        return new RoutePlanRequest("Fresno, CA", "Sacramento, CA", departure, produce);
    }

    @Test
    void ranksRoutesBestFirst() {
        RoutePlanResponse res = service(new FakeRoutes(), new FakeForecasts(p -> true), new StubRouteScorer())
                .plan(request("strawberries", DEPART));

        assertEquals("stub-0.2", res.modelVersion());
        assertEquals(2, res.routes().size());
        RoutePlanResponse.PlannedRoute best = res.routes().get(0);
        // route-1 takes 170 min, route-2 takes 190 min, so route-1 keeps more shelf life
        assertEquals(1, best.rank());
        assertEquals("route-1", best.routeId());
        assertEquals(2, res.routes().get(1).rank());
        assertTrue(best.shelfLifeRemainingPct() > res.routes().get(1).shelfLifeRemainingPct());
        assertEquals(276.0, best.distanceKm());
        assertEquals(170.0, best.googleDurationMin());
        assertEquals(170.0, best.predictedTotalDurationMin()); // stub adds no delay
        assertEquals(2.3, best.peakCargoTempC());
        assertFalse(best.encodedPolyline().isEmpty());
        assertNotNull(best.hazardsCrossed());
        assertTrue(best.reason().contains("shelf life left"), best.reason());
    }

    @Test
    void worksEndToEndWithTheV1Model() {
        // Rain everywhere: the v1 model adds about 8.7% to the drive time
        RoutePlanResponse res = service(new FakeRoutes(), new FakeForecasts(p -> true), new CoefficientRouteScorer())
                .plan(request("strawberries", DEPART));

        assertEquals("v1.0", res.modelVersion());
        RoutePlanResponse.PlannedRoute best = res.routes().get(0);
        assertEquals("route-1", best.routeId());
        assertEquals(170 * 1.0869565217391304, best.predictedTotalDurationMin(), 0.2);
        assertTrue(best.reason().contains("min of weather delay"), best.reason());
    }

    @Test
    void sendsForecastWeatherToTheScorer() {
        List<ScoreRequest> seen = new ArrayList<>();
        RouteScorer spy = req -> { seen.add(req); return new StubRouteScorer().score(req); };
        service(new FakeRoutes(), new FakeForecasts(p -> true), spy).plan(request("strawberries", DEPART));

        ScoreRequest sent = seen.get(0);
        assertEquals("strawberries", sent.produceType());
        assertEquals("reefer", sent.truckType());
        ScoreRequest.Point p = sent.routes().get(0).points().get(0);
        assertEquals(WeatherBucket.RAIN, p.weatherBucket()); // "Rain Likely" at 70%
        assertEquals(15.0, p.temperatureC());
        assertEquals(20.0, p.windSpeedKmh());
        assertEquals(DEPART, p.eta());
    }

    @Test
    void wrongProduceIsRejectedBeforeCallingGoogleOrTheScorer() {
        FakeRoutes routes = new FakeRoutes();
        RouteScorer mustNotRun = req -> fail("scorer should not be called");
        var svc = service(routes, new FakeForecasts(p -> true), mustNotRun);

        var e = assertThrows(BadRequestException.class, () -> svc.plan(request("lettuce", DEPART)));
        assertTrue(e.getMessage().contains("not yet supported"));
        assertTrue(routes.departuresAsked.isEmpty());
    }

    @Test
    void missingAddressIsRejected() {
        var svc = service(new FakeRoutes(), new FakeForecasts(p -> true), new StubRouteScorer());
        assertThrows(BadRequestException.class,
                () -> svc.plan(new RoutePlanRequest(" ", "Sacramento, CA", DEPART, "strawberries")));
    }

    @Test
    void pastDepartureBecomesNow() {
        FakeRoutes routes = new FakeRoutes();
        service(routes, new FakeForecasts(p -> true), new StubRouteScorer())
                .plan(request("strawberries", Instant.parse("2020-01-01T00:00:00Z")));
        assertTrue(routes.departuresAsked.get(0).isAfter(Instant.now()));
    }

    @Test
    void noForecastAnywhereFallsBackToCloudyAndSaysSo() {
        List<ScoreRequest> seen = new ArrayList<>();
        RouteScorer spy = req -> { seen.add(req); return new StubRouteScorer().score(req); };
        RoutePlanResponse res = service(new FakeRoutes(), new FakeForecasts(p -> false), spy)
                .plan(request("strawberries", DEPART));

        for (ScoreRequest.Point p : seen.get(0).routes().get(0).points()) {
            assertEquals(WeatherBucket.CLOUDY, p.weatherBucket());
            assertNull(p.temperatureC()); // we don't make up numbers
        }
        assertTrue(res.routes().get(0).reason().contains("No NWS forecast for"), res.routes().get(0).reason());
    }

    @Test
    void missingPointUsesTheNearestPointsForecast() {
        // Only points south of 37° have a forecast; the rest borrow the nearest one.
        List<ScoreRequest> seen = new ArrayList<>();
        RouteScorer spy = req -> { seen.add(req); return new StubRouteScorer().score(req); };
        service(new FakeRoutes(), new FakeForecasts(p -> p.lat() < 37.0), spy).plan(request("strawberries", DEPART));

        List<ScoreRequest.Point> pts = seen.get(0).routes().get(0).points();
        ScoreRequest.Point last = pts.get(pts.size() - 1); // Sacramento, no forecast of its own
        assertEquals(WeatherBucket.RAIN, last.weatherBucket());
        assertEquals(15.0, last.temperatureC());
    }

    @Test
    void nearestFoundLooksBothWays() {
        PointForecast a = new PointForecast(DEPART, DEPART, 1, 0, 0, "A");
        PointForecast b = new PointForecast(DEPART, DEPART, 2, 0, 0, "B");
        List<PointForecast> found = Arrays.asList(null, a, null, null, b);
        assertSame(a, RoutePlanService.nearestFound(found, 0));
        assertSame(a, RoutePlanService.nearestFound(found, 2));
        assertSame(b, RoutePlanService.nearestFound(found, 3));
        assertNull(RoutePlanService.nearestFound(Arrays.asList(null, null), 1));
    }
}
