package com.block.hazard_api.weather;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeywordWeatherBucketMapperTest {

    private final WeatherBucketMapper mapper = new KeywordWeatherBucketMapper();

    // The three examples listed in backend/CLAUDE.md
    @Test
    void rainAndSnowIsSnow() {
        assertEquals(WeatherBucket.SNOW, mapper.map("Rain and Snow", 80));
    }

    @Test
    void chanceThunderstormsAt60IsThunderstorm() {
        assertEquals(WeatherBucket.THUNDERSTORM, mapper.map("Chance Thunderstorms", 60));
    }

    @Test
    void slightChanceRainAt20IsCloudy() {
        assertEquals(WeatherBucket.CLOUDY, mapper.map("Slight Chance Rain", 20));
    }

    @Test
    void exactly50PercentCounts() {
        assertEquals(WeatherBucket.RAIN, mapper.map("Chance Light Rain", 50));
        assertEquals(WeatherBucket.CLOUDY, mapper.map("Chance Light Rain", 49));
    }

    @Test
    void severeKeywordsBeatMilderOnes() {
        assertEquals(WeatherBucket.ICE, mapper.map("Freezing Rain", 70));        // not rain
        assertEquals(WeatherBucket.HEAVY_RAIN, mapper.map("Heavy Rain", 90));    // not rain
        assertEquals(WeatherBucket.THUNDERSTORM, mapper.map("Showers And Thunderstorms", 70));
        assertEquals(WeatherBucket.THUNDERSTORM, mapper.map("Chance T-storms", 55));
    }

    @Test
    void nonPrecipitationBucketsIgnoreTheChance() {
        assertEquals(WeatherBucket.FOG, mapper.map("Patchy Fog", null));
        assertEquals(WeatherBucket.CLEAR, mapper.map("Mostly Sunny", 0));
        assertEquals(WeatherBucket.CLEAR, mapper.map("Mostly Clear", 0));
        assertEquals(WeatherBucket.CLOUDY, mapper.map("Mostly Cloudy", 10));
    }

    @Test
    void missingChanceMeansPrecipitationDoesNotCount() {
        assertEquals(WeatherBucket.CLOUDY, mapper.map("Rain", null));
    }

    @Test
    void unknownTextFallsBackToCloudy() {
        assertEquals(WeatherBucket.CLOUDY, mapper.map("Haze", 0));
        assertEquals(WeatherBucket.CLOUDY, mapper.map(null, null));
    }

    @Test
    void bucketJsonNamesMatchTheContract() {
        List<String> names = Arrays.stream(WeatherBucket.values()).map(WeatherBucket::jsonName).toList();
        assertEquals(List.of("clear", "cloudy", "rain", "heavy_rain", "snow", "ice", "fog", "thunderstorm"), names);
    }
}
