package com.block.hazard_api.weather;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small helpers for reading the NWS hourly forecast. No network here, so they're easy to test. */
public final class NwsForecastParsing {

    private static final double KMH_PER_MPH = 1.609344;
    private static final Pattern NUMBER = Pattern.compile("\\d+(\\.\\d+)?");

    private NwsForecastParsing() {}

    /**
     * The hourly forecast URL with ?units=si added. WITHOUT it NWS sends °F and mph.
     */
    public static String withSiUnits(String forecastHourlyUrl) {
        return forecastHourlyUrl + (forecastHourlyUrl.contains("?") ? "&" : "?") + "units=si";
    }

    /**
     * Wind speed in km/h from NWS text: "19 km/h", or a range like "10 to 15 km/h".
     * For a range we use the HIGHER number: wind matters for tall trailers, so we
     * err on the cautious side. If the text says mph (shouldn't happen with units=si),
     * we convert so nothing in mph reaches the model. Unreadable text gives 0.
     */
    public static double parseWindKmh(String windSpeed) {
        if (windSpeed == null) return 0.0;
        double max = 0.0;
        Matcher m = NUMBER.matcher(windSpeed);
        while (m.find()) {
            max = Math.max(max, Double.parseDouble(m.group()));
        }
        return windSpeed.contains("mph") ? max * KMH_PER_MPH : max;
    }

    /** Temperature in °C. With units=si NWS already sends "C"; this is a safety net for "F". */
    public static double toCelsius(double temperature, String unit) {
        return "F".equals(unit) ? (temperature - 32) * 5.0 / 9.0 : temperature;
    }

    /**
     * The hour that contains the ETA (start <= eta < end), or empty if the ETA is outside
     * the forecast (NWS hourly forecasts only go about a week ahead).
     */
    public static Optional<PointForecast> periodContaining(List<PointForecast> periods, Instant eta) {
        for (PointForecast p : periods) {
            if (!eta.isBefore(p.startTime()) && eta.isBefore(p.endTime())) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** NWS times look like "2026-10-12T14:00:00-07:00"; we store everything as UTC Instants. */
    public static Instant parseTime(String s) {
        return OffsetDateTime.parse(s).toInstant();
    }
}
