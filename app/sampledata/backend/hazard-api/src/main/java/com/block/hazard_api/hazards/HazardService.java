package com.block.hazard_api.hazards;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs every HazardFetcher, merges the results, and caches them for 5 minutes
 * so we don't hammer USGS/NWS and the app gets fast responses.
 * If one source fails, its last good data is kept and the others still work.
 */
@Service
public class HazardService {

    private static final Logger log = LoggerFactory.getLogger(HazardService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final List<HazardFetcher> fetchers;
    private final Map<String, List<Hazard>> bySource = new ConcurrentHashMap<>();
    private volatile Instant lastRefresh = Instant.EPOCH;

    // Spring hands us every @Component that implements HazardFetcher.
    public HazardService(List<HazardFetcher> fetchers) {
        this.fetchers = fetchers;
    }

    public List<Hazard> getHazards(BoundingBox box) {
        refreshIfStale();
        Instant now = Instant.now();
        List<Hazard> result = new ArrayList<>();
        for (List<Hazard> list : bySource.values()) {
            for (Hazard h : list) {
                if (h.expiresAt() != null && h.expiresAt().isBefore(now)) continue;
                if (box != null && !box.overlaps(h.lat(), h.lon(), h.radiusKm())) continue;
                result.add(h);
            }
        }
        return result;
    }

    private synchronized void refreshIfStale() {
        if (Duration.between(lastRefresh, Instant.now()).compareTo(CACHE_TTL) < 0) return;
        for (HazardFetcher f : fetchers) {
            try {
                List<Hazard> got = f.fetch();
                bySource.put(f.sourceName(), List.copyOf(got));
                log.info("{}: fetched {} hazards", f.sourceName(), got.size());
            } catch (Exception e) {
                log.warn("{} fetch failed, keeping old data: {}", f.sourceName(), e.toString());
            }
        }
        lastRefresh = Instant.now();
    }
}