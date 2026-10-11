package com.block.hazard_api.routes;

import java.util.Locale;

/** Builds the short "reason" text shown on each route card. */
public final class RouteReasons {

    private RouteReasons() {}

    public static String reason(double shelfLifePct, double delayMin, int pointsWithoutForecast, int totalPoints) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%.1f%% shelf life left on arrival", shelfLifePct));
        if (delayMin >= 1) {
            sb.append(String.format(Locale.US, "; about %d min of weather delay", Math.round(delayMin)));
        } else {
            sb.append("; no weather delay expected");
        }
        if (pointsWithoutForecast > 0) {
            sb.append(String.format(Locale.US, ". No NWS forecast for %d of %d points along this route",
                    pointsWithoutForecast, totalPoints));
        }
        return sb.append('.').toString();
    }
}
