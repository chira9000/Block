package com.block.hazard_api.weather;

/**
 * Turns an NWS forecast into one weather bucket. Behind an interface so the model owner's
 * possible LLM-based classifier can replace the keyword table without other changes.
 */
public interface WeatherBucketMapper {

    /**
     * @param shortForecast       NWS text, e.g. "Chance Light Rain"
     * @param precipChancePct     probabilityOfPrecipitation.value (0-100), or null if NWS left it out
     */
    WeatherBucket map(String shortForecast, Integer precipChancePct);
}
