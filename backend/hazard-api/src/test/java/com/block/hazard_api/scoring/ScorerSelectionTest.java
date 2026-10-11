package com.block.hazard_api.scoring;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** With scorer.type=stub (application.properties), Spring must hand out the stub. */
@SpringBootTest
class ScorerSelectionTest {

    @Autowired
    RouteScorer scorer;

    @Test
    void stubIsTheActiveScorer() {
        assertInstanceOf(StubRouteScorer.class, scorer);
    }
}
