package com.block.hazard_api.risk;

import com.block.hazard_api.hazards.GeoUtil;
import com.block.hazard_api.hazards.Hazard;
import com.block.hazard_api.hazards.HazardCategory;
import com.block.hazard_api.hazards.LatLng;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** Reads src/test/resources/risk_v2/golden.json, written by model/export/risk_v2/make_golden.py. */
final class Golden {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record File(@JsonProperty("model_version") String modelVersion,
                @JsonProperty("samples") List<Sample> samples,
                @JsonProperty("routes") List<Route> routes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Sample(@JsonProperty("lat") double lat, @JsonProperty("lon") double lon,
                  @JsonProperty("eta") String eta, @JsonProperty("alerts") List<Alert> alerts,
                  @JsonProperty("h3_cell") String h3Cell, @JsonProperty("known_cell") boolean knownCell,
                  @JsonProperty("features") double[] features,
                  @JsonProperty("incident_raw_score") double incidentRawScore,
                  @JsonProperty("incident_probability") double incidentProbability,
                  @JsonProperty("major_raw_score") double majorRawScore,
                  @JsonProperty("major_probability") double majorProbability,
                  @JsonProperty("place_rate_2021") double placeRate2021,
                  @JsonProperty("cell_mean_p") double cellMeanP,
                  @JsonProperty("p_model") double pModel,
                  @JsonProperty("p_incident") double pIncident,
                  @JsonProperty("major_share") double majorShare) {

        Instant etaInstant() {
            return Instant.parse(eta);
        }

        /** The sample's alerts as backend Hazard objects (NWS alerts with VTEC codes). */
        List<Hazard> hazards() {
            return alerts.stream().map(Alert::toHazard).toList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Alert(@JsonProperty("phenom") String phenom, @JsonProperty("sig") String sig,
                 @JsonProperty("polygon") List<List<Double>> polygon,
                 @JsonProperty("starts_at") String startsAt, @JsonProperty("expires_at") String expiresAt) {

        Hazard toHazard() {
            LatLng center = GeoUtil.polygonCenter(polygon);
            return new Hazard("golden", "NWS", phenom + "." + sig + " alert", HazardCategory.OTHER, "golden alert",
                    0.5, center.lat(), center.lng(), GeoUtil.polygonRadiusKm(polygon, center), polygon,
                    startsAt == null ? null : Instant.parse(startsAt),
                    expiresAt == null ? null : Instant.parse(expiresAt), phenom, sig);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Route(@JsonProperty("name") String name, @JsonProperty("sample_indices") List<Integer> sampleIndices,
                 @JsonProperty("expected") Expected expected) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Expected(@JsonProperty("uncapped_incident_delay_min") double uncappedIncidentDelayMin,
                    @JsonProperty("low_confidence") boolean lowConfidence,
                    @JsonProperty("expected_incident_delay_min") double expectedIncidentDelayMin,
                    @JsonProperty("major_slowdown_probability") double majorSlowdownProbability,
                    @JsonProperty("incident_risk_ratio") double incidentRiskRatio,
                    @JsonProperty("risk_score") int riskScore) {}

    private Golden() {}

    static File load() throws Exception {
        try (InputStream in = Golden.class.getResourceAsStream("/risk_v2/golden.json")) {
            return JsonMapper.builder().build().readValue(in, File.class);
        }
    }
}
