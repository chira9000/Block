# Model card: Block v1 strawberry shelf-life scorer

**Version:** v1.0 · **Produce:** strawberries · **Truck:** refrigerated (reefer) · **Built:** October 2026

## What it does

For each candidate route, the scorer estimates how much of the strawberries' shelf life is left on arrival. The app uses this number to rank routes. It combines three parts:

| Part | Based on | Type |
|---|---|---|
| **Cargo temperature** | 6 real refrigerated strawberry shipments | Linear regression (trained) |
| **Shelf life vs temperature** | One published study (7 measurements) | Lookup table + formula |
| **Weather delay** | FHWA road-weather speed reductions, plus 3 assumptions | Lookup table |

## 1. Cargo temperature

- **Data:** the public strawberry cold-chain dataset (arXiv 2103.12895): 6 US truck shipments in March, April and July 2019 (spring and summer), with up to 9 temperature loggers per trailer. After cleaning (notebook 01), 32,047 usable transit readings remain, averaged into 21,226 blocks of 15 minutes (notebook 02).
- **Model (M2):** `temp = 1.834 °C + pallet offset + 0.0986 °C × days since departure`. Pallet offsets versus front: middle −0.134 °C, rear +0.435 °C. Scoring uses the **rear** pallet, the warmest on average.
- **How it was chosen:** each model was trained on 5 shipments and tested on the 6th, for all 6 (leave-one-shipment-out). A more detailed model was kept only if it beat the simpler one on at least 4 of 6 unseen trucks.
  - Pallet beat the constant model on 4/6 trucks, and pallet + days beat pallet alone on 5/6.
  - Time of day and layer (Top/Middle/Bottom) were tested too, and won only 3/6 each, so they were left out.

**Accuracy on unseen trucks** (mean absolute error, °C):

| Held-out shipment | 1 | 2 | 3 | 4 | 5 | 6 | **Mean** |
|---|---|---|---|---|---|---|---|
| Constant (M0) | 0.707 | 0.722 | 0.866 | 1.231 | 0.655 | 0.772 | 0.825 |
| **v1 model (M2)** | 0.717 | 0.610 | 0.832 | 1.272 | 0.525 | 0.773 | **0.788** |

Typical error is about **0.8 °C**. That is only about 4.5% better than always predicting the average, because refrigeration keeps the cargo in a narrow band (90% of readings fall between 0.7 and 4.0 °C). The largest error is Shipment 4, which ran about 0.9 °C colder than the other trucks for reasons the model can't see.

## 2. Shelf life vs temperature

- **Source:** Yan et al., "Effect of Different Storage Temperature on Respiration Rate and Quality of Strawberry", *Journal of Refrigeration* (制冷学报), Issue 5, pp. 37-42.
- **Measurements:** 9 days at 0 °C, 7 at 5 °C, 5 at 10 °C, 4 at 15 °C, 3 at 20 °C, 2 at 25 °C, 8 hours at 30 °C.
- **Cross-check:** the University of South Florida Food Quality Lab reports more than 9 days at a constant 1.5 °C, which is consistent.
- **Method:** straight lines between the points, with the end values held outside 0-30 °C. Each 15-minute step spends `step hours ÷ (shelf life at that temperature × 24)` of the budget, starting from 100% at departure.
- **What it gives:** at the trucks' usual ~2.3 °C, a trip uses about **12% of shelf life per day**.

## 3. Weather delay

**Source:** FHWA Road Weather Management Program, https://ops.fhwa.dot.gov/weather/roadimpact.htm.

| Bucket | Speed reduction | Time factor | Basis |
|---|---|---|---|
| clear, cloudy | 0% | 1.000 | no slowdown |
| rain | 8% | 1.087 | FHWA range midpoint |
| heavy_rain | 10% | 1.111 | FHWA range midpoint |
| fog | 11% | 1.124 | FHWA range midpoint |
| snow | 20% | 1.250 | **assumption** |
| ice | 30% | 1.429 | **assumption** |
| thunderstorm | 10% | 1.111 | **assumption** |

Each stretch between two route points uses the worse bucket of its two ends. Example: 410 minutes of rain adds 35.7 minutes.

## Limitations

- **Small sample:** only 6 trucks, in 2 seasons (spring and summer 2019). All sensors in one truck share one trip, so this is about 29 days of trips, not thousands of independent samples. A "4 of 6" win could be chance.
- **No GPS or route data** in the dataset, so cargo temperature has **no outside-weather input** in v1. Weather affects the score only by making the trip longer.
- **Refrigerated trucks only.** It says nothing about trucks without refrigeration.
- **One shelf-life study with 7 points**, joined with straight lines. Other strawberry varieties or conditions may behave differently. Above 30 °C, shelf life is held at 8 hours.
- **Freezing damage is not modeled.** Temperatures below 0 °C are scored as ideal storage, although strawberries freeze at about −1 °C.
- **Shelf life is counted from departure** at 100%. Time and warmth before departure (harvest, precooling) are ignored, and so is the fruit's starting quality.
- **Delay values:** snow, ice and thunderstorm are assumptions, not FHWA figures. Rain, heavy rain and fog use range midpoints. The delay ignores closures, accidents and stops, and it was not validated on real truck trips.
- **Step rule:** cargo temperature is taken at the start of each 15-minute step, so the peak temperature is the last step's start, not the moment of arrival. The difference is under 0.01 °C.
- **The logged clock's time zone is unknown** in the source data. This doesn't affect v1, which uses no time-of-day input.
