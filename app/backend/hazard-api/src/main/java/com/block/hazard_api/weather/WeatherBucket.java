package com.block.hazard_api.weather;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The 8 weather buckets shared with the model owner. The JSON names must match the
 * model's training data EXACTLY: clear, cloudy, rain, heavy_rain, snow, ice, fog, thunderstorm.
 */
public enum WeatherBucket {
    CLEAR("clear"),
    CLOUDY("cloudy"),
    RAIN("rain"),
    HEAVY_RAIN("heavy_rain"),
    SNOW("snow"),
    ICE("ice"),
    FOG("fog"),
    THUNDERSTORM("thunderstorm");

    private final String jsonName;

    WeatherBucket(String jsonName) {
        this.jsonName = jsonName;
    }

    /** @JsonValue tells Jackson to write (and read) "heavy_rain" instead of "HEAVY_RAIN". */
    @JsonValue
    public String jsonName() {
        return jsonName;
    }

    /** Buckets that only count when the chance of precipitation is 50% or more. */
    public boolean isPrecipitation() {
        return this == THUNDERSTORM || this == ICE || this == SNOW || this == HEAVY_RAIN || this == RAIN;
    }
}
