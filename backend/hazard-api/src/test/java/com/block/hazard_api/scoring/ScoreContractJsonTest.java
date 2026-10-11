package com.block.hazard_api.scoring;

import static org.junit.jupiter.api.Assertions.*;

import com.block.hazard_api.weather.WeatherBucket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks our Java classes against the model owner's own test case files, so the field
 * names can't drift from the contract. We only READ model/ files, never change them.
 */
class ScoreContractJsonTest {

    // Maven runs tests from backend/hazard-api, so the repo root is two folders up.
    private static final Path TEST_CASES = Path.of("../../model/export/test_cases");
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void readsTheModelOwnersRequestFile() throws Exception {
        ScoreRequest req = json.readValue(
                Files.readString(TEST_CASES.resolve("valid_06_three_routes.request.json")), ScoreRequest.class);

        assertEquals("strawberries", req.produceType());
        assertEquals("reefer", req.truckType());
        assertEquals(3, req.routes().size());
        ScoreRequest.Route a = req.routes().get(0);
        assertEquals("A-direct", a.routeId());
        assertEquals(Instant.parse("2026-07-01T08:00:00Z"), a.departureTime());
        assertEquals(360.0, a.googleDurationMin());
        assertEquals(WeatherBucket.HEAVY_RAIN, a.points().get(2).weatherBucket());
    }

    @Test
    void readsTheModelOwnersResponseFile() throws Exception {
        ScoreResponse res = json.readValue(
                Files.readString(TEST_CASES.resolve("valid_06_three_routes.expected.json")), ScoreResponse.class);

        assertEquals("v1.0", res.modelVersion());
        ScoreResponse.RouteScore a = res.routes().get(0);
        assertEquals("A-direct", a.routeId());
        assertEquals(96.6, a.shelfLifeRemainingPct());
        assertEquals(35.7, a.predictedDelayMin());
        assertEquals(395.7, a.predictedTotalDurationMin());
        assertEquals(2.3, a.peakCargoTempC());
    }

    @Test
    void writesSnakeCaseNamesAndUtcTimes() throws Exception {
        ScoreRequest.Point p = new ScoreRequest.Point(40.4862, -74.4518, Instant.parse("2026-10-12T08:00:00Z"),
                18.5, 12.0, null, WeatherBucket.HEAVY_RAIN);
        ScoreRequest req = new ScoreRequest("strawberries", "reefer", List.of(
                new ScoreRequest.Route("A", Instant.parse("2026-10-12T08:00:00Z"), 410, List.of(p))));

        String out = json.writeValueAsString(req);
        assertTrue(out.contains("\"produce_type\":\"strawberries\""), out);
        assertTrue(out.contains("\"google_duration_min\":410.0"), out);
        assertTrue(out.contains("\"eta\":\"2026-10-12T08:00:00Z\""), out);
        assertTrue(out.contains("\"temperature_c\":18.5"), out);
        assertTrue(out.contains("\"weather_bucket\":\"heavy_rain\""), out);
        assertFalse(out.contains("precip_mm_per_hr"), "null values are left out: " + out);
    }
}
