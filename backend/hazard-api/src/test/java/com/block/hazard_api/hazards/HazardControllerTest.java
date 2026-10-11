package com.block.hazard_api.hazards;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP checks of GET /hazards. HazardService is replaced by a mock, so NWS is never called. */
@SpringBootTest
@AutoConfigureMockMvc
class HazardControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    HazardService service;

    private static final Hazard STORM = new Hazard("nws-1", "NWS", "Winter Storm Warning", HazardCategory.STORM,
            "Winter Storm Warning until Monday", 0.75, 38.8, -120.0, 30, null, null, null);

    @Test
    void bboxIsReadLonFirst() throws Exception {
        when(service.getHazards(any())).thenReturn(List.of(STORM));

        mvc.perform(get("/hazards").param("bbox", "-124.5,32.5,-114,42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("storm"))
                .andExpect(jsonPath("$[0].type").value("Winter Storm Warning"));

        verify(service).getHazards(new BoundingBox(32.5, 42.0, -124.5, -114.0));
    }

    @Test
    void oldFourParametersStillWork() throws Exception {
        mvc.perform(get("/hazards").param("minLat", "32.5").param("maxLat", "42")
                        .param("minLon", "-124.5").param("maxLon", "-114"))
                .andExpect(status().isOk());
        verify(service).getHazards(new BoundingBox(32.5, 42.0, -124.5, -114.0));
    }

    @Test
    void noFilterMeansEverything() throws Exception {
        mvc.perform(get("/hazards")).andExpect(status().isOk());
        verify(service).getHazards(isNull());
    }

    @Test
    void badBboxIs400() throws Exception {
        mvc.perform(get("/hazards").param("bbox", "1,2,3")).andExpect(status().isBadRequest());
        verify(service, never()).getHazards(any());
    }

    @Test
    void bboxAndFourParametersTogetherIs400() throws Exception {
        mvc.perform(get("/hazards").param("bbox", "-124.5,32.5,-114,42").param("minLat", "1"))
                .andExpect(status().isBadRequest());
    }
}
