package com.block.hazard_api.scoring;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * What the model sends back: one score per route, in the same order as the request.
 * Mirrors "Model API Contract (v0.1) / Response" in backend/CLAUDE.md.
 */
public record ScoreResponse(
        @JsonProperty("model_version") String modelVersion,  // log it with each result
        @JsonProperty("routes") List<RouteScore> routes
) {

    public record RouteScore(
            @JsonProperty("route_id") String routeId,
            // 0-100, higher is better. THE ranking value. 100% at departure.
            @JsonProperty("shelf_life_remaining_pct") double shelfLifeRemainingPct,
            // Extra minutes beyond Google's estimate because of weather
            @JsonProperty("predicted_delay_min") double predictedDelayMin,
            // Google estimate + predicted delay
            @JsonProperty("predicted_total_duration_min") double predictedTotalDurationMin,
            // Warmest pallet position (rear of the trailer)
            @JsonProperty("peak_cargo_temp_c") double peakCargoTempC
    ) {}
}
