package com.block.hazard_api.scoring;

import java.util.Comparator;
import java.util.List;

/** Orders scored routes best-first: the most shelf life remaining on arrival wins. */
public final class RouteRanker {

    private RouteRanker() {}

    public static List<ScoreResponse.RouteScore> rank(List<ScoreResponse.RouteScore> scores) {
        return scores.stream()
                .sorted(Comparator.comparingDouble(ScoreResponse.RouteScore::shelfLifeRemainingPct).reversed())
                .toList();
    }
}
