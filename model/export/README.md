# Block v1 strawberry model: backend handoff

This folder is everything the Spring Boot backend needs to score routes with the v1 model.
**No Python runs in production.** The backend reads `v1_model.json` and does the math in Java.
`reference_scorer.py` is the exact recipe; your Java must give the same answers on every file in `test_cases/`.

## Files

| File | What it is |
|---|---|
| `v1_model.json` | All model numbers and rules. The only file the backend needs at runtime. |
| `reference_scorer.py` | Readable Python version of the scorer: `score(request) -> response`. The source of truth for behaviour. |
| `test_cases/` | 6 valid requests with their expected responses, and 4 invalid requests with the expected error. |
| `make_test_cases.py` | Regenerates `test_cases/` from the reference scorer. |
| `tests/` | The Python test suite (pytest) that checks the scorer against the notebooks, hand calculations and random inputs. |
| `MODEL_CARD.md` | What the model is based on, how accurate it is, and its limits. |
| `VERIFICATION.md` | Test results, example outputs and the SHA-256 checksum of `v1_model.json`. |

To check that you have the exact model file: `shasum -a 256 v1_model.json` must print the checksum listed in `VERIFICATION.md`.

## Request and response

Field names are exact. Times are ISO 8601 with a time zone (use UTC, e.g. `2026-07-01T08:00:00Z`).

**Request**
```json
{
  "produce_type": "strawberries",
  "truck_type": "reefer",
  "routes": [
    {
      "route_id": "A-direct",
      "departure_time": "2026-07-01T08:00:00Z",
      "google_duration_min": 360,
      "points": [
        {"eta": "2026-07-01T08:00:00Z", "weather_bucket": "clear"},
        {"eta": "2026-07-01T09:30:00Z", "weather_bucket": "rain"}
      ]
    }
  ]
}
```
Points may carry extra fields (lat, lng, temperature, wind, precipitation). v1 ignores them.
`weather_bucket` is one of: `clear`, `cloudy`, `rain`, `heavy_rain`, `snow`, `ice`, `fog`, `thunderstorm`.

**Response**: routes are returned in the same order as in the request.
```json
{
  "model_version": "v1.0",
  "routes": [
    {
      "route_id": "A-direct",
      "shelf_life_remaining_pct": 96.6,
      "predicted_delay_min": 35.7,
      "predicted_total_duration_min": 395.7,
      "peak_cargo_temp_c": 2.3
    }
  ]
}
```

## The math, step by step

All numbers below come from `v1_model.json` (field names in `code`). Do every step per route.

**1. Weather delay.** For each stretch between two neighbouring points:
- `planned_min` = eta of the later point − eta of the earlier point, in minutes.
- Take the **worse** bucket of the two ends: the one with the larger `delay.buckets[b].speed_reduction`. On a tie, either gives the same answer.
- `extra_min` = `planned_min × (delay.buckets[worse].time_factor − 1)`.

`predicted_delay_min` = the sum of `extra_min`, **rounded to 1 decimal now**.

*Why:* if weather cuts speed by a fraction r, the same distance takes 1 / (1 − r) times as long. That's `time_factor`.

**2. Total duration.** `predicted_total_duration_min = google_duration_min + predicted_delay_min` (using the rounded delay).

**3. Split the trip into steps.** Starting at departure (minute 0), cut the total duration into `rules.step_minutes` = 15-minute steps:
- `full_steps = (int) Math.floor(total / 15.0)`
- The steps start at minutes 0, 15, 30, … and each lasts 15 minutes.
- If `rest = total − full_steps × 15` is greater than 0, add one final step starting at `full_steps × 15` and lasting `rest` minutes.

**4. Cargo temperature per step.** For the scored pallet (`cargo_temp.scored_pallet` = `"rear"`, the warmest position), at the **start** of each step:

`temp_c = base_c + pallet_offset_c["rear"] + drift_c_per_day × (step_start_min / 1440)`

**5. Shelf life per step.** Look up the shelf life at that temperature in `shelf_life.points` (sorted by `temp_c`):
- **Clamp:** at or below the first point, use the first point's days. At or above the last point, use the last point's days.
- **Otherwise** find the two neighbouring points (t0, d0) and (t1, d1) with t0 < temp ≤ t1, and compute `days = d0 + (temp − t0) × (d1 − d0) / (t1 − t0)`.

**6. Spend the budget.** Shelf life starts at 100% at departure:
- `used = Σ (step_hours / (days × 24))`, where `step_hours` = step length in minutes / 60.
- `shelf_life_remaining_pct = max(0, 100 × (1 − used))`

**7. Peak temperature.** `peak_cargo_temp_c` = the highest step temperature from step 4.

**8. Round outputs to 1 decimal**, half to even on the exact double value. In Java:
```java
static double round1(double x) {
    return new BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).doubleValue();
}
```
This matches Python's `round(x, 1)`. Note that `Math.round` rounds halves up and can differ in rare cases.

In v1, cargo temperature does **not** depend on weather. Weather changes only the trip duration, and a longer trip spends more shelf life.

## Errors

Reject the request (Python raises `ValueError`) with a clear message when:
- `produce_type` is not `"strawberries"` or `truck_type` is not `"reefer"`
- a field is missing: `produce_type`, `truck_type`, `routes`, `route_id`, `departure_time`, `google_duration_min`, `points`, `eta` or `weather_bucket`
- `routes` is empty, or a route has fewer than 2 points
- a `weather_bucket` is unknown
- etas are not strictly increasing, or an eta or departure time is not ISO 8601 with a time zone
- `google_duration_min` is not a number or is negative

The exact Python messages are in `reference_scorer.py`. Your Java messages don't have to match word for word, but every case in `test_cases/invalid_*` must be rejected.

## Implementing it in Java: `CoefficientRouteScorer`

A sketch to show the structure. It is **not compiled or tested**; the JSON reading uses Jackson, which Spring Boot already includes.

```java
public class CoefficientRouteScorer {
    private final JsonNode model;  // parsed v1_model.json

    public CoefficientRouteScorer(JsonNode model) { this.model = model; }

    double shelfLifeDaysAt(double tempC) {
        JsonNode pts = model.at("/shelf_life/points");
        JsonNode first = pts.get(0), last = pts.get(pts.size() - 1);
        if (tempC <= first.get("temp_c").asDouble()) return first.get("shelf_life_days").asDouble();
        if (tempC >= last.get("temp_c").asDouble()) return last.get("shelf_life_days").asDouble();
        for (int i = 1; i < pts.size(); i++) {
            double t0 = pts.get(i - 1).get("temp_c").asDouble(), d0 = pts.get(i - 1).get("shelf_life_days").asDouble();
            double t1 = pts.get(i).get("temp_c").asDouble(), d1 = pts.get(i).get("shelf_life_days").asDouble();
            if (tempC <= t1) return d0 + (tempC - t0) * (d1 - d0) / (t1 - t0);
        }
        throw new IllegalStateException("unreachable");
    }

    double cargoTempC(String pallet, double daysSinceDeparture) {
        JsonNode c = model.get("cargo_temp");
        return c.get("base_c").asDouble() + c.at("/pallet_offset_c/" + pallet).asDouble()
             + c.get("drift_c_per_day").asDouble() * daysSinceDeparture;
    }

    double routeDelayMin(List<Instant> etas, List<String> buckets) {
        JsonNode b = model.at("/delay/buckets");
        double total = 0.0;
        for (int i = 1; i < etas.size(); i++) {
            double plannedMin = Duration.between(etas.get(i - 1), etas.get(i)).toMillis() / 60000.0;
            String a = buckets.get(i - 1), c = buckets.get(i);
            String worse = b.get(a).get("speed_reduction").asDouble() >= b.get(c).get("speed_reduction").asDouble() ? a : c;
            total += plannedMin * (b.get(worse).get("time_factor").asDouble() - 1);
        }
        return total;
    }

    RouteScore scoreRoute(String routeId, double googleDurationMin, List<Instant> etas, List<String> buckets) {
        double delay = round1(routeDelayMin(etas, buckets));
        double total = googleDurationMin + delay;
        int fullSteps = (int) Math.floor(total / 15.0);
        String pallet = model.at("/cargo_temp/scored_pallet").asText();
        double used = 0.0, peak = Double.NEGATIVE_INFINITY;
        for (int i = 0; i <= fullSteps; i++) {
            double start = i * 15.0;
            double length = (i < fullSteps) ? 15.0 : total - fullSteps * 15.0;
            if (length <= 0) break;  // no partial last step
            double temp = cargoTempC(pallet, start / 1440);
            peak = Math.max(peak, temp);
            used += (length / 60) / (shelfLifeDaysAt(temp) * 24);
        }
        if (peak == Double.NEGATIVE_INFINITY) peak = cargoTempC(pallet, 0);  // zero-length trip
        double remaining = Math.max(0.0, 100 * (1 - used));
        return new RouteScore(routeId, round1(remaining), delay, round1(total), round1(peak));
    }
}
```
Add the input checks from **Errors** before calling `scoreRoute`, and read the step size from `rules.step_minutes` instead of the literal 15 if you prefer.

## Verifying your Java against `test_cases/`

For every `valid_*.request.json`, your scorer's response must equal `valid_*.expected.json`: same route order, same `route_id`s, and every number equal after rounding to 1 decimal. For every `invalid_*.request.json`, your scorer must reject the request; the reason is in `invalid_*.expected_error.json`.

A JUnit test that loops over the folder is the easiest way to do this. If a number is off by 0.1, the usual causes are rounding the delay too late (it must be rounded **before** adding it to the Google duration), using `Math.round` instead of `HALF_EVEN`, or taking the temperature at the middle or end of a step instead of its **start**.

To re-check the Python side: from `model/export`, run `../.venv/bin/python -m pytest tests -p no:cacheprovider`.
