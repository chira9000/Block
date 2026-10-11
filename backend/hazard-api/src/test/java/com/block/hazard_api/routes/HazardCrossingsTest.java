package com.block.hazard_api.routes;

import static org.junit.jupiter.api.Assertions.*;

import com.block.hazard_api.hazards.BoundingBox;
import com.block.hazard_api.hazards.Hazard;
import com.block.hazard_api.hazards.HazardCategory;
import com.block.hazard_api.hazards.LatLng;
import java.util.List;
import org.junit.jupiter.api.Test;

class HazardCrossingsTest {

    // A route going east along lat 33.5, from lon -119 to -116 (route points are lat, lng).
    private static final List<LatLng> ROUTE = List.of(
            new LatLng(33.5, -119.0), new LatLng(33.5, -118.0), new LatLng(33.5, -117.0), new LatLng(33.5, -116.0));

    /** A square hazard from lon1 to lon2, lat 33 to 34. Polygon points are [lon, lat]. */
    static Hazard square(String type, String headline, double severity, double lon1, double lon2) {
        List<List<Double>> poly = List.of(List.of(lon1, 33.0), List.of(lon2, 33.0),
                List.of(lon2, 34.0), List.of(lon1, 34.0), List.of(lon1, 33.0));
        return new Hazard("id-" + type + lon1, "NWS", type, HazardCategory.fromNwsEvent(type), headline, severity,
                33.5, (lon1 + lon2) / 2, 60, poly, null, null);
    }

    @Test
    void listsCrossedHazardsInRouteOrder() {
        Hazard later = square("Flood Advisory", "flood", 0.25, -116.6, -116.4);
        Hazard sooner = square("High Wind Warning", "wind", 0.75, -118.6, -118.4);
        List<RoutePlanResponse.HazardCrossed> out = HazardCrossings.crossed(ROUTE, List.of(later, sooner));

        assertEquals(2, out.size());
        assertEquals("High Wind Warning", out.get(0).type()); // reached first
        assertEquals("Flood Advisory", out.get(1).type());
        assertEquals(0.75, out.get(0).severity());
    }

    @Test
    void hazardOffTheRouteIsNotListed() {
        Hazard north = new Hazard("n", "NWS", "Heat Advisory", HazardCategory.HEAT, "heat", 0.5, 36.0, -117.5, 20,
                List.of(List.of(-118.0, 35.5), List.of(-117.0, 35.5), List.of(-117.0, 36.5),
                        List.of(-118.0, 36.5), List.of(-118.0, 35.5)), null, null);
        assertTrue(HazardCrossings.crossed(ROUTE, List.of(north)).isEmpty());
    }

    @Test
    void splitNwsAlertIsShownOnceWithHighestSeverity() {
        Hazard part1 = square("Winter Storm Warning", "same headline", 0.5, -118.6, -118.4);
        Hazard part2 = square("Winter Storm Warning", "same headline", 0.75, -117.6, -117.4);
        List<RoutePlanResponse.HazardCrossed> out = HazardCrossings.crossed(ROUTE, List.of(part1, part2));
        assertEquals(1, out.size());
        assertEquals(0.75, out.get(0).severity());
    }

    @Test
    void hazardWithoutPolygonUsesItsRadius() {
        Hazard near = new Hazard("p", "X", "Flooding", HazardCategory.FLOOD, "gauge", 0.5, 33.6, -117.0, 15, null, null, null); // ~11 km away
        Hazard far = new Hazard("q", "X", "Flooding", HazardCategory.FLOOD, "gauge", 0.5, 34.5, -117.0, 15, null, null, null);  // ~111 km away
        assertEquals(1, HazardCrossings.crossed(ROUTE, List.of(near, far)).size());
    }

    @Test
    void boxAroundCoversTheRoute() {
        assertEquals(new BoundingBox(33.5, 33.5, -119.0, -116.0), HazardCrossings.boxAround(ROUTE));
    }

    @Test
    void reasonForTheBestRoute() {
        String r = RouteReasons.reason(1, 96.54, 0.2, List.of(), 0, 10);
        assertEquals("Best option: 96.5% shelf life left on arrival; no weather delay expected; "
                + "no active weather alerts on this route.", r);
    }

    @Test
    void reasonListsHazardsWithSeverityAndCapsTheList() {
        List<RoutePlanResponse.HazardCrossed> hz = List.of(
                new RoutePlanResponse.HazardCrossed("Winter Storm Warning", "h", 0.75),
                new RoutePlanResponse.HazardCrossed("Flood Advisory", "h", 0.25),
                new RoutePlanResponse.HazardCrossed("Dust Storm Warning", "h", 1.0),
                new RoutePlanResponse.HazardCrossed("Heat Advisory", "h", 0.5));
        String r = RouteReasons.reason(2, 90.0, 25.4, hz, 2, 12);
        assertEquals("90.0% shelf life left on arrival; about 25 min of weather delay; crosses "
                + "Winter Storm Warning (severe), Flood Advisory, Dust Storm Warning (extreme) and 1 more. "
                + "No NWS forecast for 2 of 12 points along this route.", r);
    }
}
