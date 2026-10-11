package com.block.hazard_api.scoring;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * The numbers inside v1_model.json (from the model owner, model/export/v1_model.json).
 * Our copy lives in src/main/resources/model/ so it ships with the app.
 * Only the fields the scorer needs; notes and sources in the file are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record V1Model(
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("produce_type") String produceType,
        @JsonProperty("truck_type") String truckType,
        @JsonProperty("cargo_temp") CargoTemp cargoTemp,
        @JsonProperty("shelf_life") ShelfLife shelfLife,
        @JsonProperty("delay") Delay delay,
        @JsonProperty("rules") Rules rules
) {

    /** cargo_temp_c = base_c + pallet_offset_c[pallet] + drift_c_per_day * days_since_departure */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CargoTemp(
            @JsonProperty("base_c") double baseC,
            @JsonProperty("pallet_offset_c") Map<String, Double> palletOffsetC,
            @JsonProperty("drift_c_per_day") double driftCPerDay,
            @JsonProperty("scored_pallet") String scoredPallet
    ) {}

    /** Published (temperature, shelf life) points, sorted by temperature. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShelfLife(@JsonProperty("points") List<ShelfLifePoint> points) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShelfLifePoint(
            @JsonProperty("temp_c") double tempC,
            @JsonProperty("shelf_life_days") double shelfLifeDays
    ) {}

    /** Weather bucket name ("rain", ...) -> how much it slows the truck. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delay(@JsonProperty("buckets") Map<String, BucketDelay> buckets) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BucketDelay(
            @JsonProperty("speed_reduction") double speedReduction,
            @JsonProperty("time_factor") double timeFactor
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rules(
            @JsonProperty("step_minutes") int stepMinutes,
            @JsonProperty("output_decimals") int outputDecimals
    ) {}
}
