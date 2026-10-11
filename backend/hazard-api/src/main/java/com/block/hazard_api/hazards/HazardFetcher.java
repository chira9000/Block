package com.block.hazard_api.hazards;

import java.util.List;

/**
 * Every data source (USGS, NWS, later more) implements this.
 * Mark the class with @Component and Spring finds it automatically.
 */
public interface HazardFetcher {
    String sourceName();
    List<Hazard> fetch();
}
