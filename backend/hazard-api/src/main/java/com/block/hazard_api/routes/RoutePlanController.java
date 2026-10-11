package com.block.hazard_api.routes;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

/**
 * POST /routes/plan
 * {"origin": "Fresno, CA", "destination": "Sacramento, CA",
 *  "departureTime": "2026-10-12T08:00:00Z", "produceType": "strawberries"}
 * -> routes ranked best first. Errors come back as {"error": "..."}.
 */
@RestController
public class RoutePlanController {

    private static final Logger log = LoggerFactory.getLogger(RoutePlanController.class);

    private final RoutePlanService service;

    public RoutePlanController(RoutePlanService service) {
        this.service = service;
    }

    @PostMapping("/routes/plan")
    public RoutePlanResponse plan(@RequestBody RoutePlanRequest request) {
        return service.plan(request);
    }

    // Each handler turns one kind of failure into a status code + {"error": "..."}.

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<Map<String, String>> badRequest(BadRequestException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class) // malformed JSON, bad date, ...
    ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST,
                "Could not read the request body (departureTime must look like 2026-10-12T08:00:00Z)");
    }

    @ExceptionHandler(RoutesApiClient.MissingApiKeyException.class)
    ResponseEntity<Map<String, String>> noKey(RoutesApiClient.MissingApiKeyException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(RestClientException.class) // Google's Routes API failed or rejected the request
    ResponseEntity<Map<String, String>> upstream(RestClientException e) {
        log.warn("Routes API call failed: {}", e.getMessage());
        return error(HttpStatus.BAD_GATEWAY, "Could not get routes from Google; check the addresses and try again");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
