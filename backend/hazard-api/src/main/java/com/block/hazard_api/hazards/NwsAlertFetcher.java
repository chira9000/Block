package com.block.hazard_api.hazards;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Active US weather alerts from the National Weather Service. No API key needed,
 * but NWS REQUIRES a User-Agent header with contact info or it rejects the request.
 * Docs: https://www.weather.gov/documentation/services-web-api
 */
@Component
public class NwsAlertFetcher implements HazardFetcher {

    private static final Logger log = LoggerFactory.getLogger(NwsAlertFetcher.class);

    private static final String ALERTS_URL = "https://api.weather.gov/alerts/active?area=CA";

    // TODO: put your real email here
    private static final String USER_AGENT = "block-app neil.longlastname@gmail.com";

    private final RestClient http;

    public NwsAlertFetcher() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.http = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", "application/geo+json")
                .build();
    }

    @Override
    public String sourceName() {
        return "NWS";
    }

    @Override
    public List<Hazard> fetch() {
        NwsResponse body = http.get().uri(ALERTS_URL).retrieve().body(NwsResponse.class);
        List<Hazard> out = new ArrayList<>();
        if (body == null || body.features() == null) return out;

        // Count what we skip so it isn't dropped silently.
        int zoneOnly = 0, otherShape = 0;
        for (Feature f : body.features()) {
            Properties p = f.properties();
            Geometry g = f.geometry();
            if (p == null) continue;
            // Some alerts only list county/zone codes with no shape. Skipped for now.
            if (g == null) { zoneOnly++; continue; }
            // MultiPolygon etc. not handled yet.
            if (!"Polygon".equals(g.type())) { otherShape++; continue; }

            List<List<Double>> ring = outerRing(g.coordinates());
            if (ring.size() < 3) { otherShape++; continue; }

            LatLng center = GeoUtil.polygonCenter(ring);
            double radiusKm = GeoUtil.polygonRadiusKm(ring, center);

            Instant starts = parseTime(p.onset() != null ? p.onset() : p.effective());
            Instant expires = parseTime(p.ends() != null ? p.ends() : p.expires());

            out.add(new Hazard(
                    "nws-" + p.id(), "NWS", p.event(), HazardCategory.fromNwsEvent(p.event()), p.headline(),
                    severityScore(p.severity()), center.lat(), center.lng(), radiusKm,
                    ring, starts, expires));
        }
        log.info("NWS alerts: {} with shapes, skipped {} zone-only and {} other/unusable shapes",
                out.size(), zoneOnly, otherShape);
        return out;
    }

    private static double severityScore(String s) {
        if (s == null) return 0.25;
        return switch (s) {
            case "Extreme" -> 1.0;
            case "Severe" -> 0.75;
            case "Moderate" -> 0.5;
            case "Minor" -> 0.25;
            default -> 0.25;
        };
    }

    /** GeoJSON Polygon = list of rings; the first ring is the outline. Each point is [lon, lat]. */
    private static List<List<Double>> outerRing(Object coordinates) {
        List<List<Double>> ring = new ArrayList<>();
        if (!(coordinates instanceof List<?> rings) || rings.isEmpty()) return ring;
        if (!(rings.get(0) instanceof List<?> points)) return ring;
        for (Object p : points) {
            if (p instanceof List<?> xy && xy.size() >= 2
                    && xy.get(0) instanceof Number lon && xy.get(1) instanceof Number lat) {
                ring.add(List.of(lon.doubleValue(), lat.doubleValue()));
            }
        }
        return ring;
    }

    private static Instant parseTime(String s) {
        if (s == null) return null;
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    // These records mirror just the parts of the NWS JSON we need.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NwsResponse(List<Feature> features) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Feature(Properties properties, Geometry geometry) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Properties(String id, String event, String headline, String severity,
                             String effective, String onset, String expires, String ends) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Geometry(String type, Object coordinates) {}
}
