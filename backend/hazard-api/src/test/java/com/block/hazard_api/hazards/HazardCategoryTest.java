package com.block.hazard_api.hazards;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HazardCategoryTest {

    /** Every event name NWS was using nationwide on 2026-10-11, plus a few common ones. */
    private static final Map<String, HazardCategory> EXPECTED = Map.ofEntries(
            // flood
            Map.entry("Flood Warning", HazardCategory.FLOOD),
            Map.entry("Flash Flood Warning", HazardCategory.FLOOD),
            Map.entry("Flood Advisory", HazardCategory.FLOOD),
            Map.entry("Flood Watch", HazardCategory.FLOOD),
            Map.entry("Coastal Flood Advisory", HazardCategory.FLOOD),
            Map.entry("Coastal Flood Statement", HazardCategory.FLOOD),
            Map.entry("Coastal Flood Warning", HazardCategory.FLOOD),
            Map.entry("Coastal Flood Watch", HazardCategory.FLOOD),
            Map.entry("Hydrologic Outlook", HazardCategory.FLOOD),
            Map.entry("Storm Surge Warning", HazardCategory.FLOOD), // water, even though it says "Storm"
            // fire
            Map.entry("Red Flag Warning", HazardCategory.FIRE),
            Map.entry("Fire Weather Watch", HazardCategory.FIRE),
            Map.entry("Dense Smoke Advisory", HazardCategory.FIRE),
            // heat
            Map.entry("Heat Advisory", HazardCategory.HEAT),
            Map.entry("Excessive Heat Warning", HazardCategory.HEAT),
            Map.entry("Extreme Heat Warning", HazardCategory.HEAT),
            // storm (wind, winter weather, tropical, dust)
            Map.entry("Winter Storm Warning", HazardCategory.STORM),
            Map.entry("Winter Storm Watch", HazardCategory.STORM),
            Map.entry("Winter Weather Advisory", HazardCategory.STORM),
            Map.entry("Wind Advisory", HazardCategory.STORM),
            Map.entry("High Wind Warning", HazardCategory.STORM),
            Map.entry("High Wind Watch", HazardCategory.STORM),
            Map.entry("Lake Wind Advisory", HazardCategory.STORM),
            Map.entry("Gale Warning", HazardCategory.STORM),
            Map.entry("Gale Watch", HazardCategory.STORM),
            Map.entry("Tropical Storm Watch", HazardCategory.STORM),
            Map.entry("Tropical Cyclone Local Statement", HazardCategory.STORM),
            Map.entry("Blowing Dust Advisory", HazardCategory.STORM),
            Map.entry("Severe Thunderstorm Warning", HazardCategory.STORM),
            Map.entry("Tornado Warning", HazardCategory.STORM),
            Map.entry("Blizzard Warning", HazardCategory.STORM),
            Map.entry("Ice Storm Warning", HazardCategory.STORM),
            // other
            Map.entry("Small Craft Advisory", HazardCategory.OTHER),
            Map.entry("Hazardous Seas Warning", HazardCategory.OTHER),
            Map.entry("Beach Hazards Statement", HazardCategory.OTHER),
            Map.entry("Rip Current Statement", HazardCategory.OTHER),
            Map.entry("High Surf Advisory", HazardCategory.OTHER),
            Map.entry("Frost Advisory", HazardCategory.OTHER),
            Map.entry("Freeze Warning", HazardCategory.OTHER),
            Map.entry("Freeze Watch", HazardCategory.OTHER),
            Map.entry("Wind Chill Warning", HazardCategory.OTHER), // cold, not a storm
            Map.entry("Air Quality Alert", HazardCategory.OTHER),
            Map.entry("Special Weather Statement", HazardCategory.OTHER),
            Map.entry("Test Message", HazardCategory.OTHER));

    @Test
    void mapsRealNwsEventNames() {
        EXPECTED.forEach((event, category) ->
                assertEquals(category, HazardCategory.fromNwsEvent(event), event));
    }

    @Test
    void unknownOrMissingIsOther() {
        assertEquals(HazardCategory.OTHER, HazardCategory.fromNwsEvent("Something New"));
        assertEquals(HazardCategory.OTHER, HazardCategory.fromNwsEvent(null));
    }

    @Test
    void jsonNames() {
        List<String> names = Arrays.stream(HazardCategory.values()).map(HazardCategory::jsonName).toList();
        assertEquals(List.of("heat", "fire", "storm", "flood", "other"), names);
    }
}
