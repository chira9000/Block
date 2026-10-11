package com.block.hazard_api.hazards;

import java.time.Instant;
import java.util.List;

/**
 * One hazard in a single common shape, no matter which source it came from.
 * This is the JSON the app receives from GET /hazards.
 *
 * severity: 0.0 (minor) to 1.0 (avoid at all costs)
 * lat/lon:  center of the hazard
 * radiusKm: rough size of the affected area
 * polygon:  list of [lon, lat] points, or null if the hazard is just a point + radius
 */
public record Hazard(
        String id,        //identifier for a hazard
        String source,    // "USGS", "NWS", websites where weather is coming from
        String type,      // "earthquake", "Winter Storm Warning", ...
        String headline,  //what we end up printing/showing for the hazard
        double severity, //explained above    
        double lat,      
        double lon,      
        double radiusKm, 
        List<List<Double>> polygon, //for big hazards, multiple lat/long
        Instant startsAt,   //hazard start time
        Instant expiresAt   //hazard end time  
) {}