# Verification: Block v1 strawberry model

**Date:** 2026-10-11
**Environment:** Python 3.9.6, numpy 2.0.2, pandas 2.3.3, scikit-learn 1.6.1, nbconvert 7.17.1, pytest 8.4.2 (all in `model/.venv`)

## v1_model.json checksum

```
SHA-256  d06a7412a6384b17c75d2561a665cb11ed1e97885a498ef3f9f88e196ff3c390  v1_model.json
```
Check your copy with `shasum -a 256 v1_model.json` (macOS/Linux) or `certutil -hashfile v1_model.json SHA256` (Windows).

## A. Notebooks rerun from scratch

Every notebook was executed top to bottom in a fresh kernel (`jupyter nbconvert --execute --inplace`, kernel from `model/.venv`). The code was not changed.

| Notebook | Result | Outputs vs previous saved run |
|---|---|---|
| `01_explore` | all cells ran | **identical** for every cell that had saved output (row counts: 68,332 loaded → 36,790 in trip windows → 35,808 saved; 13 sensors cut at `left_cold_at`). Steps 8a/8b had no earlier saved output; their new output matches the values recorded during development. |
| `02_cargo_temp_model` | all cells ran | **identical** (MAE table, chosen model M2, fitted numbers). Step 9c had no earlier saved output; it again keeps no extra feature (M4 and M5 win 3/6 each). |
| `03_shelf_life` | all cells ran | no earlier saved output; the results match the values recorded during development (44.4 / 39.0 / 0.0 / 45.0%, per-shipment table) |
| `04_delay_model` | all cells ran, its 3 asserts pass | **identical** |

## B. Final model numbers

Taken programmatically from the fresh run of notebook 02 (full precision, stored as-is in `v1_model.json`).

| Number | Value |
|---|---|
| Model | M2: pallet + days in transit (M4 / time of day won only 3 of 6, so it was not chosen) |
| `base_c` (front pallet, day 0) | 1.8335723183772281 °C |
| `pallet_offset_c.front` | 0.0 °C |
| `pallet_offset_c.middle` | −0.1336169926020186 °C |
| `pallet_offset_c.rear` (scored) | +0.43469512604635474 °C |
| `drift_c_per_day` | +0.09864561055277332 °C/day |
| Mean error on unseen trucks | 0.788 °C (constant baseline: 0.825 °C) |

Delay time factors: clear/cloudy 1.0, rain 1.0870, heavy_rain 1.1111, fog 1.1236, snow 1.25, ice 1.4286, thunderstorm 1.1111. Shelf-life points: as published (see `MODEL_CARD.md`).

## C. Test results (`model/export/tests/`, pytest)

| Check | File | Tests | Result |
|---|---|---|---|
| **C1** test cases: 6 valid reproduced exactly, 4 invalid rejected with the expected message, plus 7 other contract errors and "extra point fields are ignored" | `test_c1_test_cases.py` | 19 | all pass |
| **C2** against the notebooks: shelf-life days at 27 temperatures and remaining % for 5 temperature histories (notebook 03); delay on 4 routes including notebook 04's own test routes; cargo temperature for 3 pallets × 7 days plus every step of a 2-day trip (notebook 02's fitted model) | `test_c2_against_notebooks.py` | 45 | all pass |
| **C3** hand-calculated: 5 days at 0 °C → 44.4%; 5 days at 10 °C → 0%; 410 min of rain → 35.7 min delay; rear day 0 = base + rear offset (2.27 °C) | `test_c3_hand_checks.py` | 4 | all pass |
| **C4** properties on 500 random requests (seed 20261011; 1-3 routes, 2-60 points, random buckets, 30 min to 7 days): shelf life within 0-100; delay ≥ 0 and total = google + delay; a longer duration never raises shelf life; a worse bucket never lowers delay; rear ≥ front at every step; scoring twice gives identical output | `test_c4_properties.py` | 7 | all pass |

**How C2 compares:** the notebook functions are **imported, not copied**. The tests read each notebook with nbformat and execute its code cells, so they always compare against the notebooks' current logic. Tolerances are 1e-12 for single values and 1e-9 for sums, to allow for numpy's different summing order.

**C5: three full runs in a row** (`pytest tests -p no:cacheprovider`, after the final code change):

| Run | Passed | Failed | Exit code |
|---|---|---|---|
| 1 | 75 | 0 | 0 |
| 2 | 75 | 0 | 0 |
| 3 | 75 | 0 | 0 |

The per-test outcomes were identical in all three runs. As an extra check, a SHA-256 fingerprint of the scorer's output on all 500 random requests plus the 6 examples was computed in three separate Python processes and came out the same every time (`c496f156…e206e765`).

The 14 warnings in each run are deprecation notices from inside matplotlib's own code, not from the scorer.

## D. Example results (`test_cases/valid_*`)

| Case | Route | Google (min) | Delay (min) | Total (min) | Shelf life left | Peak cargo temp |
|---|---|---|---|---|---|---|
| 01 short clear | short-clear | 120 | 0.0 | 120.0 | 99.0% | 2.3 °C |
| 02 long clear (3 days) | long-clear | 4320 | 0.0 | 4320.0 | 62.7% | 2.6 °C |
| 03 rain | rain-410 | 410 | 35.7 | 445.7 | 96.2% | 2.3 °C |
| 04 snow + ice | snow-ice | 480 | 122.1 | 602.1 | 94.8% | 2.3 °C |
| 05 reaches 0% (10 days) | ten-days | 14400 | 0.0 | 14400.0 | 0.0% | 3.3 °C |
| 06 three routes | A-direct | 360 | 35.7 | 395.7 | 96.6% | 2.3 °C |
| | B-coastal | 400 | 19.8 | 419.8 | 96.4% | 2.3 °C |
| | C-mountain | 400 | 121.8 | 521.8 | 95.5% | 2.3 °C |

Invalid cases: wrong produce type (`lettuce`), wrong truck type (`dry_van`), unknown bucket (`hail`), etas out of order. All are rejected with the messages stored in `test_cases/invalid_*.expected_error.json`.
