package com.block.hazard_api.hazards;

import java.time.Instant;
import java.util.List;

/**
 * One hazard in a single common shape, no matter which source it came from.
 * This is the JSON the app receives from GET /hazards.
 *
 * category: heat, fire, storm, flood or other (for app icons/filters)
 * severity: 0.0 (minor) to 1.0 (avoid at all costs)
 * lat/lon:  center of the hazard
 * radiusKm: rough size of the affected area
 * polygon:  list of [lon, lat] points, or null if the hazard is just a point + radius
 * vtecPhenom / vtecSig: NWS VTEC codes (e.g. "WS" + "W" = Winter Storm Warning); null for
 *           non-NWS hazards or alerts without a VTEC code. Used by the v2 risk model.
 */
public record Hazard(
        String id,        //identifier for a hazard
        String source,    // "USGS", "NWS", websites where weather is coming from
        String type,      // "earthquake", "Winter Storm Warning", ...
        HazardCategory category, // simple group of the type: heat, fire, storm, flood, other
        String headline,  //what we end up printing/showing for the hazard
        double severity, //explained above    
        double lat,      
        double lon,      
        double radiusKm, 
        List<List<Double>> polygon, //for big hazards, multiple lat/long
        Instant startsAt,   //hazard start time
        Instant expiresAt,  //hazard end time
        String vtecPhenom,  // optional: VTEC phenomenon code, e.g. "WS"
        String vtecSig      // optional: VTEC significance, e.g. "W" (warning), "A" (watch), "Y" (advisory)
) {
    /** Hazard without VTEC codes (every source except NWS alerts). */
    public Hazard(String id, String source, String type, HazardCategory category, String headline,
                  double severity, double lat, double lon, double radiusKm, List<List<Double>> polygon,
                  Instant startsAt, Instant expiresAt) {
        this(id, source, type, category, headline, severity, lat, lon, radiusKm, polygon, startsAt, expiresAt,
                null, null);
    }
}