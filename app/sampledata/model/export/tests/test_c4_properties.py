"""C4: rules that must hold for ANY valid request, checked on 500 random requests (fixed seed)."""
import copy
import json
import random
from datetime import datetime, timedelta, timezone

from reference_scorer import MODEL, score, step_temperatures

SEED = 20261011
N_REQUESTS = 500
BUCKETS = list(MODEL["delay"]["buckets"])
REDUCTION = {b: MODEL["delay"]["buckets"][b]["speed_reduction"] for b in BUCKETS}


def iso(moment):
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def random_route(rng, route_id):
    """2-60 points, strictly increasing etas, total 30 min to 7 days, random buckets."""
    departure = datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(minutes=rng.randint(0, 365 * 1440))
    duration_min = rng.randint(30, 7 * 1440)
    n_points = rng.randint(2, 60)
    middle = sorted(rng.sample(range(1, duration_min * 60), n_points - 2))  # seconds, all different
    offsets = [0] + middle + [duration_min * 60]
    return {
        "route_id": route_id,
        "departure_time": iso(departure),
        "google_duration_min": duration_min,
        "points": [
            {"eta": iso(departure + timedelta(seconds=s)), "weather_bucket": rng.choice(BUCKETS)} for s in offsets
        ],
    }


def random_request(rng):
    routes = [random_route(rng, f"r{k}") for k in range(rng.randint(1, 3))]
    return {"produce_type": "strawberries", "truck_type": "reefer", "routes": routes}


_rng = random.Random(SEED)
REQUESTS = [random_request(_rng) for _ in range(N_REQUESTS)]
RESPONSES = [score(r) for r in REQUESTS]


def test_generated_500_requests():
    assert len(REQUESTS) == N_REQUESTS
    assert all(1 <= len(r["routes"]) <= 3 for r in REQUESTS)
    assert all(2 <= len(route["points"]) <= 60 for r in REQUESTS for route in r["routes"])


def test_shelf_life_always_between_0_and_100():
    for i, response in enumerate(RESPONSES):
        for route in response["routes"]:
            assert 0 <= route["shelf_life_remaining_pct"] <= 100, (i, route)


def test_delay_not_negative_and_total_adds_up():
    for i, (request, response) in enumerate(zip(REQUESTS, RESPONSES)):
        for route_in, route_out in zip(request["routes"], response["routes"]):
            assert route_out["predicted_delay_min"] >= 0, (i, route_out)
            expected_total = round(route_in["google_duration_min"] + route_out["predicted_delay_min"], 1)
            assert route_out["predicted_total_duration_min"] == expected_total, (i, route_out)


def test_longer_duration_never_raises_shelf_life():
    rng = random.Random(SEED + 1)
    for i, (request, response) in enumerate(zip(REQUESTS, RESPONSES)):
        longer = copy.deepcopy(request)
        for route in longer["routes"]:
            route["google_duration_min"] += rng.randint(1, 3 * 1440)
        for before, after in zip(response["routes"], score(longer)["routes"]):
            assert after["shelf_life_remaining_pct"] <= before["shelf_life_remaining_pct"], (i, before, after)


def test_worse_bucket_never_lowers_delay():
    rng = random.Random(SEED + 2)
    for i, (request, response) in enumerate(zip(REQUESTS, RESPONSES)):
        changed = copy.deepcopy(request)
        r = rng.randrange(len(changed["routes"]))
        point = rng.choice(changed["routes"][r]["points"])
        point["weather_bucket"] = rng.choice([b for b in BUCKETS if REDUCTION[b] >= REDUCTION[point["weather_bucket"]]])
        before = response["routes"][r]["predicted_delay_min"]
        after = score(changed)["routes"][r]["predicted_delay_min"]
        assert after >= before, (i, before, after)


def test_rear_pallet_never_colder_than_front():
    for i, response in enumerate(RESPONSES):
        for route in response["routes"]:
            total = route["predicted_total_duration_min"]
            rear, front = step_temperatures(total, "rear"), step_temperatures(total, "front")
            assert len(rear) == len(front) > 0
            assert all(r >= f for r, f in zip(rear, front)), (i, route)


def test_scoring_twice_gives_identical_output():
    for i, (request, response) in enumerate(zip(REQUESTS, RESPONSES)):
        again = score(copy.deepcopy(request))
        assert again == response, i
        assert json.dumps(again) == json.dumps(response), i
