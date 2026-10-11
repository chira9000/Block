package com.block.hazard_api.routes;

import com.block.hazard_api.hazards.BoundingBox;
import com.block.hazard_api.hazards.GeoUtil;
import com.block.hazard_api.hazards.Hazard;
import com.block.hazard_api.hazards.LatLng;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Which hazards a route goes through, in the order the truck reaches them. */
public final class HazardCrossings {

    private HazardCrossings() {}

    /**
     * @param route   the route's full decoded polyline (dense points, so small hazards aren't missed)
     * @param hazards active hazards near the route
     */
    public static List<RoutePlanResponse.HazardCrossed> crossed(List<LatLng> route, List<Hazard> hazards) {
        record Hit(Hazard hazard, int index) {}
        List<Hit> hits = new ArrayList<>();
        for (Hazard h : hazards) {
            int index = firstTouch(route, h);
            if (index >= 0) hits.add(new Hit(h, index));
        }
        hits.sort(Comparator.comparingInt(Hit::index)); // order along the route

        // NWS sometimes splits one alert into several features with the same name and headline.
        // Show each only once, keeping the highest severity.
        Map<String, RoutePlanResponse.HazardCrossed> unique = new LinkedHashMap<>();
        for (Hit hit : hits) {
            Hazard h = hit.hazard();
            String key = h.type() + "|" + h.headline();
            RoutePlanResponse.HazardCrossed seen = unique.get(key);
            if (seen == null || h.severity() > seen.severity()) {
                unique.put(key, new RoutePlanResponse.HazardCrossed(h.type(), h.headline(), h.severity()));
            }
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * Index of the first route point that touches the hazard, or -1.
     * Polygon hazards use GeoUtil.firstCrossingIndex; hazards without a polygon
     * (just a center + radius) count when the route passes within the radius.
     */
    static int firstTouch(List<LatLng> route, Hazard h) {
        if (h.polygon() != null && h.polygon().size() >= 3) {
            return GeoUtil.firstCrossingIndex(route, h.polygon());
        }
        for (int i = 0; i < route.size(); i++) {
            LatLng p = route.get(i);
            if (GeoUtil.haversineKm(p.lat(), p.lng(), h.lat(), h.lon()) <= h.radiusKm()) return i;
        }
        return -1;
    }

    /** The smallest box around the route, used to ask HazardService only for nearby hazards. */
    public static BoundingBox boxAround(List<LatLng> route) {
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (LatLng p : route) {
            minLat = Math.min(minLat, p.lat());
            maxLat = Math.max(maxLat, p.lat());
            minLon = Math.min(minLon, p.lng());
            maxLon = Math.max(maxLon, p.lng());
        }
        return new BoundingBox(minLat, maxLat, minLon, maxLon);
    }
}
