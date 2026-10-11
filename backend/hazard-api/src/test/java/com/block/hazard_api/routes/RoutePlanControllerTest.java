package com.block.hazard_api.routes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level checks of POST /routes/plan. The API key is forced empty here so these tests
 * never call Google, even on a machine where GOOGLE_ROUTES_API_KEY is set.
 */
@SpringBootTest(properties = "google.routes.api-key=")
@AutoConfigureMockMvc
class RoutePlanControllerTest {

    @Autowired
    MockMvc mvc;

    private static final String BODY = """
            {"origin": "Fresno, CA", "destination": "Sacramento, CA",
             "departureTime": "2099-10-12T08:00:00Z", "produceType": "%s"}
            """;

    @Test
    void unsupportedProduceIs400WithErrorMessage() throws Exception {
        mvc.perform(post("/routes/plan").contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("lettuce")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("produceType 'lettuce' is not yet supported (only strawberries)"));
    }

    @Test
    void badDepartureTimeIs400() throws Exception {
        String body = """
                {"origin": "A", "destination": "B", "departureTime": "tomorrow", "produceType": "strawberries"}
                """;
        mvc.perform(post("/routes/plan").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void missingApiKeyIs503() throws Exception {
        mvc.perform(post("/routes/plan").contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("strawberries")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
    }
}
