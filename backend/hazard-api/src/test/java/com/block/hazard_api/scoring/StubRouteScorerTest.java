package com.block.hazard_api.scoring;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class StubRouteScorerTest {

    private static ScoreRequest.Route route(String id, double durationMin) {
        return new ScoreRequest.Route(id, Instant.parse("2026-10-12T08:00:00Z"), durationMin, List.of());
    }

    @Test
    void stubFollowsTheContractFormula() {
        ScoreRequest req = new ScoreRequest("strawberries", "reefer", List.of(route("A", 410)));
        ScoreResponse res = new StubRouteScorer().score(req);

        assertEquals("stub-0.2", res.modelVersion());
        ScoreResponse.RouteScore a = res.routes().get(0);
        assertEquals("A", a.routeId());
        // 410 min = 6.83 h; 100 * (1 - 6.83 / 197) = 96.53
        assertEquals(96.53, a.shelfLifeRemainingPct(), 0.01);
        assertEquals(0.0, a.predictedDelayMin());
        assertEquals(410.0, a.predictedTotalDurationMin());
        assertEquals(2.3, a.peakCargoTempC());
    }

    @Test
    void veryLongTripBottomsOutAtZero() {
        ScoreRequest req = new ScoreRequest("strawberries", "reefer", List.of(route("A", 300 * 60)));
        assertEquals(0.0, new StubRouteScorer().score(req).routes().get(0).shelfLifeRemainingPct());
    }

    @Test
    void routesComeBackInRequestOrder() {
        ScoreRequest req = new ScoreRequest("strawberries", "reefer",
                List.of(route("slow", 600), route("fast", 300)));
        List<String> ids = new StubRouteScorer().score(req).routes().stream()
                .map(ScoreResponse.RouteScore::routeId).toList();
        assertEquals(List.of("slow", "fast"), ids);
    }

    @Test
    void rankerPutsMostShelfLifeFirst() {
        List<ScoreResponse.RouteScore> scores = List.of(
                new ScoreResponse.RouteScore("A", 96.6, 0, 0, 2.3),
                new ScoreResponse.RouteScore("C", 95.5, 0, 0, 2.3),
                new ScoreResponse.RouteScore("B", 97.0, 0, 0, 2.3));
        List<String> ids = RouteRanker.rank(scores).stream().map(ScoreResponse.RouteScore::routeId).toList();
        assertEquals(List.of("B", "A", "C"), ids);
    }
}
