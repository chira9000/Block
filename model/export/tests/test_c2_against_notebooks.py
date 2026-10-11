"""C2: the scorer matches the notebooks' own functions and fitted model.

The notebook functions are IMPORTED, not copied: each notebook's code cells are read with
nbformat and executed (from model/notebooks, so their relative data paths work). If a
notebook's logic ever changes, these tests compare against the new logic automatically.
"""
import contextlib
import io
import os
import random
from datetime import timedelta

import matplotlib

matplotlib.use("Agg")  # notebooks draw charts; no window needed
import matplotlib.pyplot as plt  # noqa: E402
import nbformat  # noqa: E402
import pandas as pd  # noqa: E402
import pytest  # noqa: E402

import reference_scorer as scorer  # noqa: E402
from conftest import NOTEBOOK_DIR  # noqa: E402


def run_notebook_cells(filename, count):
    """Execute the first `count` code cells of a notebook and return its variables."""
    cells = [c for c in nbformat.read(NOTEBOOK_DIR / filename, as_version=4).cells if c.cell_type == "code"]
    namespace = {}
    old_dir, old_show = os.getcwd(), plt.show
    os.chdir(NOTEBOOK_DIR)
    plt.show = lambda *args, **kwargs: plt.close("all")
    try:
        with contextlib.redirect_stdout(io.StringIO()):
            for cell in cells[:count]:
                exec(cell.source, namespace)
    finally:
        os.chdir(old_dir)
        plt.show = old_show
    return namespace


@pytest.fixture(scope="module")
def nb02():
    return run_notebook_cells("02_cargo_temp_model.ipynb", 3)  # load, leave-one-out, final fit


@pytest.fixture(scope="module")
def nb03():
    return run_notebook_cells("03_shelf_life.ipynb", 3)  # data, interpolation, budget


@pytest.fixture(scope="module")
def nb04():
    return run_notebook_cells("04_delay_model.ipynb", 3)  # table, delay function, tests


# --------------------------------------------------------------------------- shelf life (notebook 03)

TEMPERATURES = [-10, -1, -0.01, 0, 0.5, 1, 1.5, 2, 2.27, 3, 4, 4.99, 5, 7.5, 10, 12, 14.9, 15,
                17, 20, 22.2, 25, 27.5, 29.99, 30, 31, 40]


@pytest.mark.parametrize("temp_c", TEMPERATURES)
def test_shelf_life_days_matches_notebook_03(nb03, temp_c):
    assert scorer.shelf_life_days_at(temp_c) == pytest.approx(float(nb03["shelf_life_days_at"](temp_c)), abs=1e-12)


def make_histories():
    rng = random.Random(3)
    return {
        "3 days at 2C": [2.0] * (3 * 96),
        "slow warm-up 0 to 8C": [i * 8 / 400 for i in range(400)],
        "door open: 2C, 6 h at 15C, 2C": [2.0] * 200 + [15.0] * 24 + [2.0] * 200,
        "outside the tested range": [-6.5] * 12 + [35.0] * 4 + [1.0] * 100,
        "random 0-30C": [rng.uniform(0, 30) for _ in range(150)],
    }


@pytest.mark.parametrize("label", sorted(make_histories()))
def test_shelf_life_remaining_matches_notebook_03(nb03, label):
    temps = make_histories()[label]
    expected = nb03["shelf_life_remaining_pct"](temps, hours_per_step=0.25)
    assert scorer.shelf_life_remaining_pct(temps, [0.25] * len(temps)) == pytest.approx(expected, abs=1e-9)


# --------------------------------------------------------------------------- delay (notebook 04)

def notebook_04_routes(nb04):
    make_route = nb04["make_route"]
    start = pd.Timestamp("2026-07-01 08:00")
    mixed = [
        {"eta": start + pd.Timedelta(minutes=m), "weather_bucket": b}
        for m, b in zip(range(0, 360, 45), ["clear", "rain", "heavy_rain", "fog", "snow", "ice", "thunderstorm", "cloudy"])
    ]
    return {
        "410 min clear": make_route(410, 10, "clear"),
        "410 min rain": make_route(410, 10, "rain"),
        "clear to snow": [
            {"eta": "2026-07-01 08:00", "weather_bucket": "clear"},
            {"eta": "2026-07-01 09:00", "weather_bucket": "snow"},
        ],
        "every bucket": mixed,
    }


@pytest.mark.parametrize("label", ["410 min clear", "410 min rain", "clear to snow", "every bucket"])
def test_delay_matches_notebook_04(nb04, label):
    route = notebook_04_routes(nb04)[label]
    points = [(pd.Timestamp(p["eta"]).to_pydatetime(), p["weather_bucket"]) for p in route]
    expected = nb04["extra_delay_minutes"](route)
    assert scorer.route_delay_min(points) == pytest.approx(expected, abs=1e-9)


# --------------------------------------------------------------------------- cargo temperature (notebook 02)

def test_notebook_02_chose_m2(nb02):
    assert nb02["chosen"] == "M2"


@pytest.mark.parametrize("days", [0, 0.25, 1, 2.5, 5, 7, 10])
def test_cargo_temp_matches_notebook_02_model(nb02, days):
    grid = pd.DataFrame({"pallet": ["Front", "Middle", "Rear"], "days_in_transit": [days] * 3})
    expected = nb02["final"].predict(nb02["make_features"](grid, use_days=True))
    for pallet, value in zip(grid["pallet"], expected):
        assert scorer.cargo_temp_c(pallet.lower(), days) == pytest.approx(value, abs=1e-12)


def test_step_temperatures_use_days_since_departure(nb02):
    """The scorer's step temperatures equal the notebook model at each step's start time."""
    total_min = 2 * 1440 + 7  # 2 days and 7 minutes -> last step is 7 minutes long
    starts = [s for s, _ in scorer.split_into_steps(total_min)]
    grid = pd.DataFrame({"pallet": ["Rear"] * len(starts), "days_in_transit": [s / 1440 for s in starts]})
    expected = nb02["final"].predict(nb02["make_features"](grid, use_days=True))
    assert scorer.step_temperatures(total_min, "rear") == pytest.approx(list(expected), abs=1e-12)
    assert timedelta(minutes=starts[-1]) == timedelta(days=2)
