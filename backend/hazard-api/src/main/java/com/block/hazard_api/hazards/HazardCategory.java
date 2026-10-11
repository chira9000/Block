package com.block.hazard_api.hazards;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

/**
 * A simple group for each hazard, so the app can pick an icon/color and filter.
 * JSON values: "heat", "fire", "storm", "flood", "other".
 */
public enum HazardCategory {
    HEAT("heat"),
    FIRE("fire"),
    STORM("storm"),
    FLOOD("flood"),
    OTHER("other"); // frost, rip currents, air quality, marine-only, test messages, ...

    private final String jsonName;

    HazardCategory(String jsonName) {
        this.jsonName = jsonName;
    }

    @JsonValue
    public String jsonName() {
        return jsonName;
    }

    private record Rule(HazardCategory category, List<String> keywords) {}

    // Checked IN THIS ORDER; the first match wins. Order matters:
    //  - "Storm Surge Warning" is flood (water), so flood is checked before storm.
    //  - "Wind Chill" and "Freeze" are cold, not storms, so they're caught as OTHER before storm.
    //  - "Small Craft Advisory" is boats only, so OTHER before storm's "Gale"/"Wind".
    private static final List<Rule> RULES = List.of(
            new Rule(FLOOD, List.of("Flood", "Storm Surge", "Tsunami", "Hydrologic")),
            new Rule(FIRE, List.of("Fire", "Red Flag", "Smoke")),
            new Rule(HEAT, List.of("Heat")),
            new Rule(OTHER, List.of("Wind Chill", "Cold", "Freeze", "Frost", "Small Craft", "Marine")),
            new Rule(STORM, List.of("Storm", "Thunderstorm", "Tornado", "Hurricane", "Tropical", "Typhoon",
                    "Wind", "Gale", "Blizzard", "Winter", "Snow", "Ice", "Sleet", "Freezing", "Squall",
                    "Dust", "Hail")));

    /** Category for an NWS event name such as "Winter Storm Warning". Unknown names are OTHER. */
    public static HazardCategory fromNwsEvent(String event) {
        if (event == null) return OTHER;
        for (Rule rule : RULES) {
            for (String keyword : rule.keywords()) {
                if (event.contains(keyword)) return rule.category();
            }
        }
        return OTHER;
    }
}
