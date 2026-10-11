package com.block.hazard_api.routes;

import com.block.hazard_api.hazards.GeoUtil;
import com.block.hazard_api.hazards.LatLng;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Picks a point every ~24 km (15 miles) along a route and works out the truck's ETA there.
 * The first point is the origin (ETA = departure), the last is the destination
 * (ETA = departure + the route's total duration).
 *
 * ETAs come from the step durations: inside a step, time is spread evenly over distance,
 * so a slow city step and a fast highway step each get realistic times.
 */
public final class RouteSampler {

    /** ~15 miles, inside the contract's "every 10-20 miles". */
    public static final double DEFAULT_SPACING_KM = 24.0;

    private RouteSampler() {}

    public static List<SampledPoint> sample(List<RouteStep> steps, Instant departure) {
        return sample(steps, departure, DEFAULT_SPACING_KM);
    }

    public static List<SampledPoint> sample(List<RouteStep> steps, Instant departure, double spacingKm) {
        List<SampledPoint> out = new ArrayList<>();
        LatLng origin = firstPoint(steps);
        if (origin == null) return out; // empty route
        out.add(new SampledPoint(origin, departure));

        double totalKm = totalLengthKm(steps);
        double kmSoFar = 0;        // distance driven so far
        double secondsSoFar = 0;   // time driven so far
        double nextSampleKm = spacingKm;
        LatLng destination = origin;

        for (RouteStep step : steps) {
            List<LatLng> pts = step.points();
            double stepKm = lengthKm(pts);
            if (stepKm == 0) {
                // A step with no distance (e.g. waiting at a ferry): just add its time.
                secondsSoFar += step.durationSeconds();
                continue;
            }
            for (int i = 1; i < pts.size(); i++) {
                LatLng a = pts.get(i - 1), b = pts.get(i);
                double segKm = distanceKm(a, b);
                double segSeconds = step.durationSeconds() * segKm / stepKm; // this segment's share of the step's time

                // Drop a sample at every multiple of spacingKm that falls inside this segment
                // (but not at the very end; the destination is added separately).
                while (nextSampleKm <= kmSoFar + segKm && nextSampleKm < totalKm) {
                    double f = (nextSampleKm - kmSoFar) / segKm; // 0 = at a, 1 = at b
                    LatLng p = new LatLng(a.lat() + f * (b.lat() - a.lat()), a.lng() + f * (b.lng() - a.lng()));
                    out.add(new SampledPoint(p, plusSeconds(departure, secondsSoFar + f * segSeconds)));
                    nextSampleKm += spacingKm;
                }
                kmSoFar += segKm;
                secondsSoFar += segSeconds;
                destination = b;
            }
        }

        addDestination(out, new SampledPoint(destination, plusSeconds(departure, secondsSoFar)));
        return out;
    }

    /**
     * The model rejects ETAs that don't strictly increase, so drop any sample that isn't
     * before the destination (can happen with zero-duration steps near the end).
     * The origin is always kept.
     */
    private static void addDestination(List<SampledPoint> out, SampledPoint dest) {
        while (out.size() > 1 && !dest.eta().isAfter(out.get(out.size() - 1).eta())) {
            out.remove(out.size() - 1);
        }
        if (dest.eta().isAfter(out.get(out.size() - 1).eta())) out.add(dest);
    }

    private static LatLng firstPoint(List<RouteStep> steps) {
        for (RouteStep s : steps) {
            if (!s.points().isEmpty()) return s.points().get(0);
        }
        return null;
    }

    private static double totalLengthKm(List<RouteStep> steps) {
        double km = 0;
        for (RouteStep s : steps) km += lengthKm(s.points());
        return km;
    }

    private static double lengthKm(List<LatLng> pts) {
        double km = 0;
        for (int i = 1; i < pts.size(); i++) km += distanceKm(pts.get(i - 1), pts.get(i));
        return km;
    }

    private static double distanceKm(LatLng a, LatLng b) {
        return GeoUtil.haversineKm(a.lat(), a.lng(), b.lat(), b.lng());
    }

    private static Instant plusSeconds(Instant t, double seconds) {
        return t.plusMillis(Math.round(seconds * 1000));
    }
}
