package com.block.hazard_api.weather;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NwsForecastParsingTest {

    @Test
    void hourlyUrlAsksForSiUnits() {
        String url = "https://api.weather.gov/gridpoints/LOX/155,45/forecast/hourly";
        assertEquals(url + "?units=si", NwsForecastParsing.withSiUnits(url));
        assertEquals(url + "?foo=1&units=si", NwsForecastParsing.withSiUnits(url + "?foo=1"));
    }

    @Test
    void parsesSingleWindSpeed() {
        assertEquals(19.0, NwsForecastParsing.parseWindKmh("19 km/h"), 1e-9);
    }

    @Test
    void windRangeUsesTheHigherNumber() {
        assertEquals(15.0, NwsForecastParsing.parseWindKmh("10 to 15 km/h"), 1e-9);
    }

    @Test
    void windInMphIsConvertedToKmh() {
        assertEquals(16.09, NwsForecastParsing.parseWindKmh("10 mph"), 0.01);
    }

    @Test
    void unreadableWindIsZero() {
        assertEquals(0.0, NwsForecastParsing.parseWindKmh(null), 1e-9);
        assertEquals(0.0, NwsForecastParsing.parseWindKmh("Calm"), 1e-9);
    }

    @Test
    void celsiusPassesThroughAndFahrenheitIsConverted() {
        assertEquals(18.0, NwsForecastParsing.toCelsius(18, "C"), 1e-9);
        assertEquals(0.0, NwsForecastParsing.toCelsius(32, "F"), 1e-9);
    }

    @Test
    void parsesRealNwsSampleIntoMetricForecasts() throws Exception {
        List<PointForecast> periods = loadSample();
        assertEquals(3, periods.size());

        PointForecast first = periods.get(0);
        // "2026-10-10T22:00:00-07:00" is 05:00 UTC the next day
        assertEquals(Instant.parse("2026-10-11T05:00:00Z"), first.startTime());
        assertEquals(26.0, first.temperatureC(), 1e-9);  // already °C thanks to units=si
        assertEquals(10.0, first.windSpeedKmh(), 1e-9);
        assertEquals(20, first.precipChancePct());
        assertEquals("Slight Chance Rain Showers", first.shortForecast());
    }

    @Test
    void picksThePeriodContainingTheEta() throws Exception {
        List<PointForecast> periods = loadSample();
        // 06:30 UTC falls in the second hour (06:00-07:00 UTC)
        PointForecast p = NwsForecastParsing.periodContaining(periods, Instant.parse("2026-10-11T06:30:00Z")).orElseThrow();
        assertEquals("Rain Likely", p.shortForecast());
        assertEquals(67, p.precipChancePct());
        // The exact start of an hour belongs to that hour, not the one before
        PointForecast q = NwsForecastParsing.periodContaining(periods, Instant.parse("2026-10-11T06:00:00Z")).orElseThrow();
        assertEquals(Instant.parse("2026-10-11T06:00:00Z"), q.startTime());
    }

    @Test
    void etaOutsideTheForecastGivesNothing() throws Exception {
        assertTrue(NwsForecastParsing.periodContaining(loadSample(), Instant.parse("2027-01-01T00:00:00Z")).isEmpty());
    }

    /** Three real periods saved from api.weather.gov (Los Angeles, units=si). */
    private static List<PointForecast> loadSample() throws Exception {
        try (InputStream in = NwsForecastParsingTest.class.getResourceAsStream("/nws/hourly-sample.json")) {
            var body = JsonMapper.builder().build().readValue(in, NwsForecastClient.HourlyResponse.class);
            return NwsForecastClient.toPointForecasts(body);
        }
    }
}
