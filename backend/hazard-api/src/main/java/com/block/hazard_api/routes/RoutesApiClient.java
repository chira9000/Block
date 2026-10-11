package com.block.hazard_api.routes;

import com.block.hazard_api.hazards.GeoUtil;
import com.block.hazard_api.hazards.LatLng;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Asks Google's Routes API for 1-3 candidate routes between two addresses.
 * Docs: https://developers.google.com/maps/documentation/routes/compute_route_directions
 *
 * The API key comes from the GOOGLE_ROUTES_API_KEY environment variable (via
 * application.properties). NEVER put the key itself in a file.
 * Google charges per request, so don't call this in a loop.
 */
@Component
public class RoutesApiClient {

    private static final String URL = "https://routes.googleapis.com/directions/v2:computeRoutes";

    // Only ask for what we use: Google bills by field and the response stays small.
    static final String FIELD_MASK = String.join(",",
            "routes.duration",
            "routes.distanceMeters",
            "routes.polyline.encodedPolyline",
            "routes.legs.steps.staticDuration",
            "routes.legs.steps.polyline.encodedPolyline");

    private final String apiKey;
    private final RestClient http;

    public RoutesApiClient(@Value("${google.routes.api-key:}") String apiKey) {
        this.apiKey = apiKey;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** Thrown when the API key environment variable isn't set. */
    public static class MissingApiKeyException extends RuntimeException {
        public MissingApiKeyException() {
            super("Routes API key not configured (set the GOOGLE_ROUTES_API_KEY environment variable)");
        }
    }

    /**
     * Candidate routes, Google's recommended route first.
     * departure must be in the future (Google rejects traffic-aware requests in the past).
     */
    public List<CandidateRoute> computeRoutes(String origin, String destination, Instant departure) {
        if (apiKey == null || apiKey.isBlank()) throw new MissingApiKeyException();
        RoutesResponse body = http.post().uri(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", FIELD_MASK)
                .body(requestBody(origin, destination, departure))
                .retrieve()
                .body(RoutesResponse.class);
        return toCandidates(body);
    }

    /** The JSON we send. Package-private so a test can check it. */
    static Map<String, Object> requestBody(String origin, String destination, Instant departure) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("origin", Map.of("address", origin));
        body.put("destination", Map.of("address", destination));
        body.put("travelMode", "DRIVE");
        body.put("routingPreference", "TRAFFIC_AWARE"); // use predicted traffic at the departure time
        body.put("departureTime", departure.toString()); // ISO 8601 UTC, e.g. 2026-10-12T08:00:00Z
        body.put("computeAlternativeRoutes", true);
        body.put("units", "METRIC");
        return body;
    }

    /** Google's JSON -> our CandidateRoutes. Package-private for tests. */
    static List<CandidateRoute> toCandidates(RoutesResponse body) {
        List<CandidateRoute> out = new ArrayList<>();
        if (body == null || body.routes() == null) return out;
        for (GRoute r : body.routes()) {
            if (r.polyline() == null || r.polyline().encodedPolyline() == null) continue;
            double durationSec = parseSeconds(r.duration());
            double distanceKm = r.distanceMeters() == null ? 0.0 : r.distanceMeters() / 1000.0;
            out.add(new CandidateRoute(r.polyline().encodedPolyline(), distanceKm, durationSec,
                    stepsOf(r, durationSec)));
        }
        return out;
    }

    /**
     * The route's steps, with their times scaled so they add up to the route's traffic-aware
     * duration. (Step times from Google ignore traffic; the route total includes it.)
     * If Google sent no steps, the whole route becomes one step.
     */
    private static List<RouteStep> stepsOf(GRoute r, double routeSeconds) {
        List<List<LatLng>> stepPoints = new ArrayList<>();
        List<Double> stepSeconds = new ArrayList<>();
        if (r.legs() != null) {
            for (Leg leg : r.legs()) {
                if (leg.steps() == null) continue;
                for (Step s : leg.steps()) {
                    if (s.polyline() == null || s.polyline().encodedPolyline() == null) continue;
                    stepPoints.add(GeoUtil.decodePolyline(s.polyline().encodedPolyline()));
                    stepSeconds.add(parseSeconds(s.staticDuration()));
                }
            }
        }
        double staticTotal = stepSeconds.stream().mapToDouble(Double::doubleValue).sum();
        if (stepPoints.isEmpty() || staticTotal <= 0) {
            return List.of(new RouteStep(GeoUtil.decodePolyline(r.polyline().encodedPolyline()), routeSeconds));
        }
        double scale = routeSeconds / staticTotal;
        List<RouteStep> steps = new ArrayList<>();
        for (int i = 0; i < stepPoints.size(); i++) {
            steps.add(new RouteStep(stepPoints.get(i), stepSeconds.get(i) * scale));
        }
        return steps;
    }

    /** Google writes durations like "1234s". Missing or unreadable gives 0. */
    static double parseSeconds(String s) {
        if (s == null || !s.endsWith("s")) return 0.0;
        try {
            return Double.parseDouble(s.substring(0, s.length() - 1));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    // These records mirror just the parts of Google's JSON we asked for in FIELD_MASK.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoutesResponse(List<GRoute> routes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GRoute(String duration, Integer distanceMeters, Polyline polyline, List<Leg> legs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Leg(List<Step> steps) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Step(String staticDuration, Polyline polyline) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Polyline(String encodedPolyline) {}
}
