package com.block.hazard_api.weather;

import java.time.Instant;

/**
 * The NWS forecast for one place during one hour, already in metric units.
 *
 * precipChancePct: 0-100, or null if NWS didn't give one
 * shortForecast:   NWS text such as "Chance Light Rain" (feed it to a WeatherBucketMapper)
 */
public record PointForecast(
        Instant startTime,
        Instant endTime,
        double temperatureC,
        double windSpeedKmh,
        Integer precipChancePct,
        String shortForecast
) {}
