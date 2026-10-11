package com.block.hazard_api.scoring;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Placeholder scorer (stub-0.2 from backend/CLAUDE.md) so the app works end to end
 * before the real model is plugged in. No weather delay yet.
 *
 * The numbers: the trained cargo model puts the warmest pallet at about 2.3°C, and at that
 * temperature strawberries last about 197 hours, so shelf life drops ~12% per day of driving.
 *
 * Active when scorer.type=stub (also the default if the property is missing).
 */
@Component
@ConditionalOnProperty(name = "scorer.type", havingValue = "stub", matchIfMissing = true)
public class StubRouteScorer implements RouteScorer {

    static final String MODEL_VERSION = "stub-0.2";
    private static final double PEAK_CARGO_TEMP_C = 2.3;
    private static final double SHELF_LIFE_HOURS = 197.0;

    @Override
    public ScoreResponse score(ScoreRequest request) {
        List<ScoreResponse.RouteScore> scores = new ArrayList<>();
        for (ScoreRequest.Route route : request.routes()) {
            double hours = route.googleDurationMin() / 60.0;
            double shelfLifePct = Math.max(0.0, 100.0 * (1.0 - hours / SHELF_LIFE_HOURS));
            scores.add(new ScoreResponse.RouteScore(
                    route.routeId(),
                    shelfLifePct,
                    0.0,                       // no weather delay in the stub
                    route.googleDurationMin(), // so total = Google's estimate
                    PEAK_CARGO_TEMP_C));
        }
        return new ScoreResponse(MODEL_VERSION, scores);
    }
}
