package com.block.hazard_api.weather;

import com.block.hazard_api.hazards.LatLng;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Gets the NWS hourly forecast for a point at the time the truck will be there.
 * Two calls: /points/{lat},{lng} tells us which forecast grid the point is in,
 * then that grid's forecastHourly?units=si gives one-hour periods for about a week.
 * Docs: https://www.weather.gov/documentation/services-web-api
 */
@Component
public class NwsForecastClient {

    private static final Logger log = LoggerFactory.getLogger(NwsForecastClient.class);

    private static final String POINTS_URL = "https://api.weather.gov/points/%.4f,%.4f";
    private static final String USER_AGENT = "block-app neil.longlastname@gmail.com";

    // NWS updates hourly forecasts about once an hour, so 30 minutes is fresh enough.
    private static final Duration FORECAST_TTL = Duration.ofMinutes(30);

    private final RestClient http;

    // Point (rounded) -> its forecastHourly URL. A point's grid basically never changes.
    private final Map<String, String> gridUrlByPoint = new ConcurrentHashMap<>();
    // forecastHourly URL -> periods. Nearby route points share a grid, so they share this.
    private final Map<String, CachedForecast> forecastByGrid = new ConcurrentHashMap<>();

    private record CachedForecast(Instant fetchedAt, List<PointForecast> periods) {}

    public NwsForecastClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.http = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", "application/geo+json")
                .build();
    }

    /**
     * The forecast hour containing the ETA at this point, or empty if NWS has nothing
     * (point outside the US, ETA more than ~a week ahead, or NWS is down).
     */
    public Optional<PointForecast> forecastAt(LatLng point, Instant eta) {
        try {
            String gridUrl = gridUrlFor(point);
            if (gridUrl == null) return Optional.empty();
            return NwsForecastParsing.periodContaining(periodsFor(gridUrl), eta);
        } catch (Exception e) {
            log.warn("NWS forecast failed for {}: {}", point, e.getMessage());
            return Optional.empty();
        }
    }

    private String gridUrlFor(LatLng point) {
        // NWS only accepts up to 4 decimal places; Locale.US makes sure it's "34.05", not "34,05".
        String url = String.format(Locale.US, POINTS_URL, point.lat(), point.lng());
        String cached = gridUrlByPoint.get(url);
        if (cached != null) return cached;
        PointsResponse body = http.get().uri(url).retrieve().body(PointsResponse.class);
        String gridUrl = body == null || body.properties() == null ? null : body.properties().forecastHourly();
        if (gridUrl != null) gridUrlByPoint.put(url, gridUrl);
        return gridUrl;
    }

    private List<PointForecast> periodsFor(String gridUrl) {
        CachedForecast cached = forecastByGrid.get(gridUrl);
        if (cached != null && cached.fetchedAt().plus(FORECAST_TTL).isAfter(Instant.now())) {
            return cached.periods();
        }
        HourlyResponse body = http.get().uri(NwsForecastParsing.withSiUnits(gridUrl))
                .retrieve().body(HourlyResponse.class);
        List<PointForecast> periods = toPointForecasts(body);
        forecastByGrid.put(gridUrl, new CachedForecast(Instant.now(), periods));
        return periods;
    }

    /** Converts the raw NWS JSON into metric PointForecasts. Package-private for tests. */
    static List<PointForecast> toPointForecasts(HourlyResponse body) {
        List<PointForecast> out = new ArrayList<>();
        if (body == null || body.properties() == null || body.properties().periods() == null) return out;
        for (Period p : body.properties().periods()) {
            if (p.startTime() == null || p.endTime() == null) continue;
            out.add(new PointForecast(
                    NwsForecastParsing.parseTime(p.startTime()),
                    NwsForecastParsing.parseTime(p.endTime()),
                    p.temperature() == null ? 0.0 : NwsForecastParsing.toCelsius(p.temperature(), p.temperatureUnit()),
                    NwsForecastParsing.parseWindKmh(p.windSpeed()),
                    p.probabilityOfPrecipitation() == null ? null : p.probabilityOfPrecipitation().value(),
                    p.shortForecast()));
        }
        return out;
    }

    // These records mirror just the parts of the NWS JSON we need.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PointsResponse(PointsProperties properties) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PointsProperties(String forecastHourly) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HourlyResponse(HourlyProperties properties) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HourlyProperties(List<Period> periods) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Period(String startTime, String endTime, Double temperature, String temperatureUnit,
                  Percent probabilityOfPrecipitation, String windSpeed, String shortForecast) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Percent(Integer value) {}
}
