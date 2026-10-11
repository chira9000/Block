package com.block.hazard_api.scoring;

import com.block.hazard_api.weather.WeatherBucket;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * What the backend sends to the model: all candidate routes for one trip.
 * Mirrors "Model API Contract (v0.1) / Request" in backend/CLAUDE.md. Field names are exact.
 */
public record ScoreRequest(
        @JsonProperty("produce_type") String produceType,  // only "strawberries" for now
        @JsonProperty("truck_type") String truckType,      // only "reefer" for now
        @JsonProperty("routes") List<Route> routes
) {

    public record Route(
            @JsonProperty("route_id") String routeId,
            @JsonProperty("departure_time") Instant departureTime,       // UTC
            @JsonProperty("google_duration_min") double googleDurationMin,
            @JsonProperty("points") List<Point> points                  // in route order
    ) {}

    /** One sampled point. Weather is the forecast for this place at this ETA. Metric units. */
    @JsonInclude(JsonInclude.Include.NON_NULL) // leave out weather values we don't have
    public record Point(
            @JsonProperty("lat") Double lat,
            @JsonProperty("lng") Double lng,
            @JsonProperty("eta") Instant eta,                           // UTC
            @JsonProperty("temperature_c") Double temperatureC,
            @JsonProperty("wind_speed_kmh") Double windSpeedKmh,
            @JsonProperty("precip_mm_per_hr") Double precipMmPerHr,
            @JsonProperty("weather_bucket") WeatherBucket weatherBucket
    ) {}
}
