package com.block.hazard_api.scoring;

import static org.junit.jupiter.api.Assertions.*;

import com.block.hazard_api.weather.WeatherBucket;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** With scorer.type=coefficient (application.properties), Spring must hand out the v1 scorer. */
@SpringBootTest
class ScorerSelectionTest {

    @Autowired
    RouteScorer scorer;

    @Test
    void coefficientScorerIsActiveAndLoadsTheModel() {
        assertInstanceOf(CoefficientRouteScorer.class, scorer);

        ScoreRequest.Route route = new ScoreRequest.Route("A", Instant.parse("2026-07-01T08:00:00Z"), 60,
                List.of(point("2026-07-01T08:00:00Z"), point("2026-07-01T09:00:00Z")));
        ScoreResponse res = scorer.score(new ScoreRequest("strawberries", "reefer", List.of(route)));
        assertEquals("v1.0", res.modelVersion());
    }

    private static ScoreRequest.Point point(String eta) {
        return new ScoreRequest.Point(null, null, Instant.parse(eta), null, null, null, WeatherBucket.CLEAR);
    }
}
