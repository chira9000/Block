package com.block.hazard_api.routes;

import java.util.List;
import java.util.Locale;

/**
 * Builds the short "reason" text shown on each route card, e.g.
 * "Best option: 96.5% shelf life left on arrival; about 15 min of weather delay;
 *  crosses Winter Storm Warning (severe), Flood Advisory."
 */
public final class RouteReasons {

    /** Show at most this many hazard names; the rest become "and N more". */
    static final int MAX_HAZARD_NAMES = 3;

    private RouteReasons() {}

    public static String reason(int rank, double shelfLifePct, double delayMin,
                                List<RoutePlanResponse.HazardCrossed> hazards,
                                int pointsWithoutForecast, int totalPoints) {
        StringBuilder sb = new StringBuilder();
        if (rank == 1) sb.append("Best option: ");
        sb.append(String.format(Locale.US, "%.1f%% shelf life left on arrival", shelfLifePct));

        if (delayMin >= 1) {
            sb.append(String.format(Locale.US, "; about %d min of weather delay", Math.round(delayMin)));
        } else {
            sb.append("; no weather delay expected");
        }

        if (hazards.isEmpty()) {
            sb.append("; no active weather alerts on this route");
        } else {
            sb.append("; crosses ");
            int shown = Math.min(hazards.size(), MAX_HAZARD_NAMES);
            for (int i = 0; i < shown; i++) {
                if (i > 0) sb.append(", ");
                sb.append(hazards.get(i).type()).append(severityLabel(hazards.get(i).severity()));
            }
            if (hazards.size() > shown) sb.append(" and ").append(hazards.size() - shown).append(" more");
        }

        if (pointsWithoutForecast > 0) {
            sb.append(String.format(Locale.US, ". No NWS forecast for %d of %d points along this route",
                    pointsWithoutForecast, totalPoints));
        }
        return sb.append('.').toString();
    }

    /** Only call out the serious ones, so the text stays short. */
    private static String severityLabel(double severity) {
        if (severity >= 1.0) return " (extreme)";
        if (severity >= 0.75) return " (severe)";
        return "";
    }
}
