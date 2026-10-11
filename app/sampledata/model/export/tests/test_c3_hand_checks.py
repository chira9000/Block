"""C3: answers worked out with simple arithmetic, independent of the scorer's own code."""
import json

from conftest import EXPORT_DIR
from reference_scorer import cargo_temp_c, score, shelf_life_remaining_pct

STEPS_IN_5_DAYS = 5 * 24 * 4  # 15-minute steps


def test_5_days_at_0C_leaves_44_4_pct():
    expected = 100 * (1 - 5 / 9)  # shelf life at 0 °C is 9 days; 5 of them are used
    actual = shelf_life_remaining_pct([0.0] * STEPS_IN_5_DAYS, [0.25] * STEPS_IN_5_DAYS)
    assert round(expected, 1) == 44.4
    assert round(actual, 1) == 44.4


def test_5_days_at_10C_leaves_0_pct():
    expected = max(0.0, 100 * (1 - 5 / 5))  # shelf life at 10 °C is 5 days
    actual = shelf_life_remaining_pct([10.0] * STEPS_IN_5_DAYS, [0.25] * STEPS_IN_5_DAYS)
    assert round(expected, 1) == 0.0
    assert round(actual, 1) == 0.0


def test_410_minutes_of_rain_adds_35_7_minutes():
    expected = round(410 * (1 / (1 - 0.08) - 1), 1)  # FHWA rain: 8% slower
    points = [{"eta": f"2026-07-01T{8 + m // 60:02d}:{m % 60:02d}:00Z", "weather_bucket": "rain"} for m in range(0, 411, 41)]
    request = {
        "produce_type": "strawberries",
        "truck_type": "reefer",
        "routes": [{"route_id": "rain", "departure_time": points[0]["eta"], "google_duration_min": 410, "points": points}],
    }
    route = score(request)["routes"][0]
    assert expected == 35.7
    assert route["predicted_delay_min"] == 35.7
    assert route["predicted_total_duration_min"] == 445.7


def test_rear_pallet_on_day_0_is_base_plus_rear_offset():
    with open(EXPORT_DIR / "v1_model.json", encoding="utf-8") as f:
        cargo = json.load(f)["cargo_temp"]
    expected = cargo["base_c"] + cargo["pallet_offset_c"]["rear"]
    assert cargo_temp_c("rear", 0) == expected
    assert round(expected, 2) == 2.27  # 1.83 + 0.43, the numbers printed by notebook 02
