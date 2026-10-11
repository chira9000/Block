package com.block.hazard_api.scoring;

/**
 * Scores candidate routes. The rest of the backend only ever depends on this interface,
 * so the stub can be swapped for CoefficientRouteScorer (v1) or HttpRouteScorer (v2)
 * by changing the "scorer.type" property in application.properties.
 */
public interface RouteScorer {
    ScoreResponse score(ScoreRequest request);
}
