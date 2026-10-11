"""Reference scorer for the Block v1 strawberry model.

score(request) -> response follows the backend API contract. Every number comes from
v1_model.json, so this file is the step-by-step recipe the Java backend reproduces.
Plain Python only (no numpy), so each line maps directly to Java.
"""
import json
from datetime import datetime
from pathlib import Path

MODEL_PATH = Path(__file__).with_name("v1_model.json")


def load_model(path=MODEL_PATH):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


MODEL = load_model()


# ----------------------------------------------------------------------------- building blocks

def shelf_life_days_at(temp_c, model=MODEL):
    """Shelf life in days at a constant temperature: straight lines between the published points."""
    points = model["shelf_life"]["points"]
    # Outside the tested range, hold the end values (clamp)
    if temp_c <= points[0]["temp_c"]:
        return points[0]["shelf_life_days"]
    if temp_c >= points[-1]["temp_c"]:
        return points[-1]["shelf_life_days"]
    for low, high in zip(points[:-1], points[1:]):
        if temp_c <= high["temp_c"]:
            t0, d0 = low["temp_c"], low["shelf_life_days"]
            t1, d1 = high["temp_c"], high["shelf_life_days"]
            return d0 + (temp_c - t0) * (d1 - d0) / (t1 - t0)


def shelf_life_remaining_pct(step_temps_c, step_hours, model=MODEL):
    """% of shelf life left after the given steps (not rounded). Each step spends hours / total life."""
    used = 0.0
    for temp_c, hours in zip(step_temps_c, step_hours):
        used += hours / (shelf_life_days_at(temp_c, model) * 24)
    return max(0.0, 100 * (1 - used))


def cargo_temp_c(pallet, days_since_departure, model=MODEL):
    """Predicted cargo temperature (°C) for a pallet position ("front", "middle" or "rear")."""
    cargo = model["cargo_temp"]
    return cargo["base_c"] + cargo["pallet_offset_c"][pallet] + cargo["drift_c_per_day"] * days_since_departure


def split_into_steps(total_min, model=MODEL):
    """List of (start_min, length_min) steps covering the trip; the last step may be shorter."""
    step_min = model["rules"]["step_minutes"]
    full_steps = int(total_min / step_min)  # same arithmetic as Java: (int) Math.floor(totalMin / 15.0)
    steps = [(i * step_min, step_min) for i in range(full_steps)]
    rest_min = total_min - full_steps * step_min
    if rest_min > 0:
        steps.append((full_steps * step_min, rest_min))
    return steps


def step_temperatures(total_min, pallet, model=MODEL):
    """Cargo temperature at the START of each step."""
    return [cargo_temp_c(pallet, start_min / 1440, model) for start_min, _ in split_into_steps(total_min, model)]


def route_delay_min(points, model=MODEL):
    """Extra minutes from weather (not rounded). points: list of (eta datetime, weather_bucket) in order."""
    buckets = model["delay"]["buckets"]
    total = 0.0
    for (eta_a, bucket_a), (eta_b, bucket_b) in zip(points[:-1], points[1:]):
        planned_min = (eta_b - eta_a).total_seconds() / 60
        # The worse bucket of the stretch's two ends (larger speed reduction)
        if buckets[bucket_a]["speed_reduction"] >= buckets[bucket_b]["speed_reduction"]:
            worse = bucket_a
        else:
            worse = bucket_b
        total += planned_min * (buckets[worse]["time_factor"] - 1)
    return total


# ----------------------------------------------------------------------------- input checks

def _require(obj, key, where):
    if not isinstance(obj, dict) or key not in obj:
        raise ValueError(f"missing field {key!r} in {where}")
    return obj[key]


def _parse_utc(text, where):
    """Parse an ISO 8601 time that includes a time zone, e.g. '2026-07-01T08:00:00Z'."""
    if not isinstance(text, str):
        raise ValueError(f"{where} must be an ISO 8601 string, got {text!r}")
    try:
        parsed = datetime.fromisoformat(text[:-1] + "+00:00" if text.endswith("Z") else text)
    except ValueError:
        raise ValueError(f"{where} is not a valid ISO 8601 time: {text!r}") from None
    if parsed.tzinfo is None:
        raise ValueError(f"{where} must include a time zone (e.g. 'Z' for UTC): {text!r}")
    return parsed


def _is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


# ----------------------------------------------------------------------------- main entry point

def score(request, model=MODEL):
    """Score every route in the request. Raises ValueError with a clear message for bad input."""
    produce_type = _require(request, "produce_type", "request")
    truck_type = _require(request, "truck_type", "request")
    routes = _require(request, "routes", "request")

    if produce_type != model["produce_type"]:
        raise ValueError(f"unsupported produce_type {produce_type!r}: v1 supports only {model['produce_type']!r}")
    if truck_type != model["truck_type"]:
        raise ValueError(f"unsupported truck_type {truck_type!r}: v1 supports only {model['truck_type']!r}")
    if not isinstance(routes, list) or not routes:
        raise ValueError("routes must be a non-empty list")

    rules = model["rules"]
    pallet = model["cargo_temp"]["scored_pallet"]
    decimals = rules["output_decimals"]
    results = []

    for r, route in enumerate(routes):
        route_id = _require(route, "route_id", f"routes[{r}]")
        where = f"route {route_id!r}"
        _parse_utc(_require(route, "departure_time", where), f"{where} departure_time")
        google_min = _require(route, "google_duration_min", where)
        points_in = _require(route, "points", where)

        if not _is_number(google_min):
            raise ValueError(f"{where}: google_duration_min must be a number, got {google_min!r}")
        if google_min < 0:
            raise ValueError(f"{where}: google_duration_min must not be negative, got {google_min}")
        if not isinstance(points_in, list) or len(points_in) < 2:
            count = len(points_in) if isinstance(points_in, list) else 0
            raise ValueError(f"{where} needs at least 2 points, got {count}")

        points = []
        for p, point in enumerate(points_in):
            point_where = f"{where}, point {p}"
            eta = _parse_utc(_require(point, "eta", point_where), f"{point_where} eta")
            bucket = _require(point, "weather_bucket", point_where)
            if bucket not in model["delay"]["buckets"]:
                raise ValueError(f"{point_where}: unknown weather_bucket {bucket!r}")
            if points and eta <= points[-1][0]:
                raise ValueError(f"{where}: etas must be in increasing order (point {p} is not after point {p - 1})")
            points.append((eta, bucket))

        # 1. Delay from weather, rounded first so total = google + delay exactly
        delay_min = round(route_delay_min(points, model), decimals)
        total_min = google_min + delay_min

        # 2. Cargo temperature of the scored pallet at the start of each 15-minute step
        steps = split_into_steps(total_min, model)
        temps = step_temperatures(total_min, pallet, model)

        # 3. Shelf-life budget over those steps
        remaining = shelf_life_remaining_pct(temps, [length / 60 for _, length in steps], model)
        peak = max(temps) if temps else cargo_temp_c(pallet, 0, model)  # zero-length trip: departure temperature

        results.append({
            "route_id": route_id,
            "shelf_life_remaining_pct": round(remaining, decimals),
            "predicted_delay_min": delay_min,
            "predicted_total_duration_min": round(total_min, decimals),
            "peak_cargo_temp_c": round(peak, decimals),
        })

    return {"model_version": model["model_version"], "routes": results}
