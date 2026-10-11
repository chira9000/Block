"""Write the example request/response pairs in test_cases/, using reference_scorer.py.

Run from model/export:  ../.venv/bin/python make_test_cases.py
"""
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

from reference_scorer import score

OUT_DIR = Path(__file__).with_name("test_cases")
DEPARTURE = datetime(2026, 7, 1, 8, 0, tzinfo=timezone.utc)


def iso(minutes_after_departure):
    return (DEPARTURE + timedelta(minutes=minutes_after_departure)).strftime("%Y-%m-%dT%H:%M:%SZ")


def make_route(route_id, buckets, step_min):
    """A route with one point every step_min minutes; google duration = time to the last point."""
    return {
        "route_id": route_id,
        "departure_time": iso(0),
        "google_duration_min": step_min * (len(buckets) - 1),
        "points": [{"eta": iso(i * step_min), "weather_bucket": b} for i, b in enumerate(buckets)],
    }


def make_request(*routes):
    return {"produce_type": "strawberries", "truck_type": "reefer", "routes": list(routes)}


VALID = {
    "valid_01_short_clear": make_request(make_route("short-clear", ["clear"] * 5, 30)),  # 2 hours
    "valid_02_long_clear": make_request(make_route("long-clear", ["clear", "cloudy"] * 6 + ["clear"], 360)),  # 3 days
    "valid_03_rain": make_request(make_route("rain-410", ["rain"] * 11, 41)),  # 410 min of rain
    "valid_04_snow_ice": make_request(
        make_route("snow-ice", ["clear", "snow", "snow", "ice", "ice", "snow", "cloudy", "clear", "clear"], 60)
    ),
    "valid_05_reaches_zero": make_request(make_route("ten-days", ["clear"] * 11, 1440)),  # 10 days
    "valid_06_three_routes": make_request(
        make_route("A-direct", ["clear", "rain", "heavy_rain", "rain", "clear"], 90),
        make_route("B-coastal", ["fog", "fog", "cloudy", "clear", "clear", "clear"], 80),
        make_route("C-mountain", ["clear", "snow", "ice", "thunderstorm", "clear"], 100),
    ),
}


def invalid_cases():
    wrong_produce = make_request(make_route("r1", ["clear"] * 3, 60))
    wrong_produce["produce_type"] = "lettuce"

    wrong_truck = make_request(make_route("r1", ["clear"] * 3, 60))
    wrong_truck["truck_type"] = "dry_van"

    unknown_bucket = make_request(make_route("r1", ["clear", "hail", "clear"], 60))

    out_of_order = make_request(make_route("r1", ["clear"] * 3, 60))
    points = out_of_order["routes"][0]["points"]
    points[1]["eta"], points[2]["eta"] = points[2]["eta"], points[1]["eta"]

    return {
        "invalid_01_wrong_produce": wrong_produce,
        "invalid_02_wrong_truck": wrong_truck,
        "invalid_03_unknown_bucket": unknown_bucket,
        "invalid_04_etas_out_of_order": out_of_order,
    }


def write_json(path, data):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def main():
    OUT_DIR.mkdir(exist_ok=True)
    for name, request in VALID.items():
        write_json(OUT_DIR / f"{name}.request.json", request)
        write_json(OUT_DIR / f"{name}.expected.json", score(request))
    for name, request in invalid_cases().items():
        try:
            score(request)
        except ValueError as error:
            write_json(OUT_DIR / f"{name}.request.json", request)
            write_json(OUT_DIR / f"{name}.expected_error.json", {"error": "ValueError", "message": str(error)})
        else:
            raise AssertionError(f"{name} should have been rejected")
    print(f"Wrote {len(VALID)} valid and 4 invalid cases to {OUT_DIR}")


if __name__ == "__main__":
    main()
