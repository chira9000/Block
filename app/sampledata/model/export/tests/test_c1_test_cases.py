"""C1: every example in test_cases/ is reproduced exactly, and every bad request is rejected."""
import copy
import json

import pytest

from conftest import TEST_CASES_DIR
from reference_scorer import score

VALID = sorted(p.name.replace(".request.json", "") for p in TEST_CASES_DIR.glob("valid_*.request.json"))
INVALID = sorted(p.name.replace(".request.json", "") for p in TEST_CASES_DIR.glob("invalid_*.request.json"))


def load(name):
    with open(TEST_CASES_DIR / name, encoding="utf-8") as f:
        return json.load(f)


def test_expected_number_of_cases():
    assert len(VALID) == 6 and len(INVALID) == 4


@pytest.mark.parametrize("name", VALID)
def test_valid_case_reproduces_expected_response(name):
    assert score(load(f"{name}.request.json")) == load(f"{name}.expected.json")


@pytest.mark.parametrize("name", INVALID)
def test_invalid_case_raises_expected_error(name):
    expected = load(f"{name}.expected_error.json")
    with pytest.raises(ValueError) as error:
        score(load(f"{name}.request.json"))
    assert str(error.value) == expected["message"]


# The other errors from the API contract, built from a valid example
BASE = load("valid_01_short_clear.request.json")


def broken(change):
    request = copy.deepcopy(BASE)
    change(request)
    return request


OTHER_ERRORS = {
    "missing produce_type": (broken(lambda r: r.pop("produce_type")), "missing field 'produce_type' in request"),
    "missing route field": (
        broken(lambda r: r["routes"][0].pop("google_duration_min")),
        "missing field 'google_duration_min' in route 'short-clear'",
    ),
    "missing point field": (
        broken(lambda r: r["routes"][0]["points"][2].pop("weather_bucket")),
        "missing field 'weather_bucket' in route 'short-clear', point 2",
    ),
    "fewer than 2 points": (
        broken(lambda r: r["routes"][0].update(points=r["routes"][0]["points"][:1])),
        "route 'short-clear' needs at least 2 points, got 1",
    ),
    "negative duration": (
        broken(lambda r: r["routes"][0].update(google_duration_min=-5)),
        "route 'short-clear': google_duration_min must not be negative, got -5",
    ),
    "repeated eta": (
        broken(lambda r: r["routes"][0]["points"][1].update(eta=r["routes"][0]["points"][0]["eta"])),
        "route 'short-clear': etas must be in increasing order (point 1 is not after point 0)",
    ),
    "eta without time zone": (
        broken(lambda r: r["routes"][0]["points"][0].update(eta="2026-07-01T08:00:00")),
        "route 'short-clear', point 0 eta must include a time zone (e.g. 'Z' for UTC): '2026-07-01T08:00:00'",
    ),
}


@pytest.mark.parametrize("label", sorted(OTHER_ERRORS))
def test_other_contract_errors(label):
    request, message = OTHER_ERRORS[label]
    with pytest.raises(ValueError) as error:
        score(request)
    assert str(error.value) == message


def test_extra_point_fields_are_ignored():
    """Points may carry lat/lng/temperature/etc.; v1 ignores them."""
    request = copy.deepcopy(BASE)
    for point in request["routes"][0]["points"]:
        point.update(lat=36.9, lng=-121.8, temperature_c=31.0, wind_kmh=20, precipitation_mm=0)
    assert score(request) == score(BASE)
