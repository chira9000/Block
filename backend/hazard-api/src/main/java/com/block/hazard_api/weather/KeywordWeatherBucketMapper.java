package com.block.hazard_api.weather;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The shared keyword table from backend/CLAUDE.md ("Weather bucket mapping").
 * Keep this the ONLY copy of the table in the backend.
 */
@Component
public class KeywordWeatherBucketMapper implements WeatherBucketMapper {

    private static final Logger log = LoggerFactory.getLogger(KeywordWeatherBucketMapper.class);

    /** Minimum chance (%) for a precipitation bucket to count. Below it we use cloudy. */
    static final int MIN_PRECIP_CHANCE_PCT = 50;

    private record Rule(WeatherBucket bucket, List<String> keywords) {}

    // Checked IN THIS ORDER, most severe first; the first match wins.
    // So "Rain and Snow" is snow (snow is checked before rain).
    private static final List<Rule> RULES = List.of(
            new Rule(WeatherBucket.THUNDERSTORM, List.of("Thunderstorm", "T-storms")),
            new Rule(WeatherBucket.ICE, List.of("Freezing Rain", "Sleet", "Ice")),
            new Rule(WeatherBucket.SNOW, List.of("Snow", "Blizzard", "Flurries")),
            new Rule(WeatherBucket.HEAVY_RAIN, List.of("Heavy Rain")),
            new Rule(WeatherBucket.RAIN, List.of("Rain", "Showers", "Drizzle")),
            new Rule(WeatherBucket.FOG, List.of("Fog")),
            new Rule(WeatherBucket.CLOUDY, List.of("Cloudy", "Overcast")),
            new Rule(WeatherBucket.CLEAR, List.of("Sunny", "Clear")));

    @Override
    public WeatherBucket map(String shortForecast, Integer precipChancePct) {
        WeatherBucket bucket = matchKeywords(shortForecast);
        if (bucket == null) {
            // Not in the table: use cloudy, and log it so the table can be extended.
            log.warn("No weather bucket for shortForecast '{}'; using cloudy", shortForecast);
            return WeatherBucket.CLOUDY;
        }
        // Chance rule: "Slight Chance Rain" at 20% shouldn't add rain delays.
        int chance = precipChancePct == null ? 0 : precipChancePct;
        if (bucket.isPrecipitation() && chance < MIN_PRECIP_CHANCE_PCT) {
            return WeatherBucket.CLOUDY;
        }
        return bucket;
    }

    private static WeatherBucket matchKeywords(String shortForecast) {
        if (shortForecast == null) return null;
        for (Rule rule : RULES) {
            for (String keyword : rule.keywords()) {
                if (shortForecast.contains(keyword)) return rule.bucket();
            }
        }
        return null;
    }
}
