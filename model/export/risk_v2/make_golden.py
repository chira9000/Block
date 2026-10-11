"""Write golden.json: reference answers for the Java port of the v2 risk model.

For ~20 California points / ETAs / NWS alert combinations it records the feature vector
(in meta["features"] order) and both LightGBM models' outputs, computed here with the real
lightgbm library. It also records the per-point formulas and five route-level results, so
the backend can check its whole risk calculation against Python.

Run from model/:
    DYLD_FALLBACK_LIBRARY_PATH=.venv/lib/python3.9/site-packages/sklearn/.dylibs \
        .venv/bin/python export/risk_v2/make_golden.py
(lightgbm needs libomp; scikit-learn's wheel ships one, so nothing is installed system-wide.)
"""
import json
import math
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import h3
import lightgbm as lgb
import numpy as np
import pandas as pd

HERE = Path(__file__).parent
META = json.loads((HERE / "risk_model_meta.json").read_text())
# round_trip: correctly rounded floats, like Java's Double.parseDouble (pandas' default parser can be 1 ULP off)
CELLS = pd.read_csv(HERE / "road_cells_features.csv", float_precision="round_trip").set_index("h3_cell")
INCIDENT = lgb.Booster(model_file=str(HERE / "incident_model.txt"))
MAJOR = lgb.Booster(model_file=str(HERE / "major_model.txt"))

# Route formula constants (named ASSUMPTIONs, same values as the Java code)
ENCOUNTERS_PER_100KM = 0.3     # ASSUMPTION: delaying incidents a truck meets per 100 km, average road and time
MINOR_INCIDENT_DELAY_MIN = 10  # ASSUMPTION
MAJOR_INCIDENT_DELAY_MIN = 30  # ASSUMPTION
MAX_INCIDENT_DELAY_MIN = 120   # cap on expected incident delay per route
RISK_SCORE_SCALE_MIN = 30
CAP_LOW, CAP_HIGH = META["route_formula"]["cap_low"], META["route_formula"]["cap_high"]


# ----------------------------------------------------------------------------- features

def point_in_polygon(lat, lon, polygon):
    """Ray casting, written exactly like GeoUtil.pointInPolygon. Polygon points are [lon, lat]."""
    inside = False
    n = len(polygon)
    j = n - 1
    for i in range(n):
        xi, yi = polygon[i]
        xj, yj = polygon[j]
        if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def parse_time(s):
    return None if s is None else datetime.fromisoformat(s.replace("Z", "+00:00"))


def active_covering(alerts, lat, lon, eta):
    """Alerts with a VTEC code that cover the point and are active at the ETA (start <= eta < end)."""
    out = []
    for a in alerts:
        if a["phenom"] is None:
            continue
        starts, expires = parse_time(a["starts_at"]), parse_time(a["expires_at"])
        if starts is not None and eta < starts:
            continue
        if expires is not None and eta >= expires:
            continue
        if point_in_polygon(lat, lon, a["polygon"]):
            out.append(a)
    return out


def features_for(lat, lon, eta_text, alerts):
    eta = parse_time(eta_text)
    cell = h3.latlng_to_cell(lat, lon, META["h3_resolution"])
    known = cell in CELLS.index
    if known:
        row = CELLS.loc[cell]
        log_rate, motorway, road_points = row["log_cell_rate"], row["is_motorway"], row["road_points"]
        place_rate, cell_mean_p = row["place_rate_2021"], row["cell_mean_p"]
    else:
        log_rate, motorway, road_points = META["unknown_cell_log_rate"], 0, 1
        place_rate, cell_mean_p = CELLS["place_rate_2021"].min(), META["overall_mean_p_2021"]

    hits = active_covering(alerts, lat, lon, eta)
    phenoms = {a["phenom"] for a in hits}
    values = {group: int(any(p in codes for p in phenoms)) for group, codes in META["nws_groups"].items()}
    values["nws_max_level"] = max([META["nws_level"].get(a["sig"], 0) for a in hits], default=0)
    values["nws_active_count"] = len(hits)
    values.update(fire_active=0, log_fire_acres=0, storm_flood=0, storm_any=0)
    values.update(is_motorway=int(motorway), road_points=int(road_points), log_cell_rate=float(log_rate))
    local = eta.astimezone(ZoneInfo(META["time_features_timezone"]))
    values.update(local_hour=local.hour, day_of_week=local.weekday(), month=local.month)  # Monday = 0

    vector = [float(values[name]) for name in META["features"]]
    return cell, known, vector, float(place_rate), float(cell_mean_p), hits


def platt(p, a, b):
    logit = math.log(p / (1 - p))
    return 1 / (1 + math.exp(-(a * logit + b)))


# ----------------------------------------------------------------------------- sample inputs

def square(lat, lon, half=0.2):
    """A closed square polygon ([lon, lat] points) around a point."""
    return [[lon - half, lat - half], [lon + half, lat - half], [lon + half, lat + half],
            [lon - half, lat + half], [lon - half, lat - half]]


def alert(phenom, sig, polygon, starts="2000-01-01T00:00:00Z", expires="2100-01-01T00:00:00Z"):
    return {"phenom": phenom, "sig": sig, "polygon": polygon, "starts_at": starts, "expires_at": expires}


def known_cell_points():
    """Centers of a spread of known road cells: motorway and not, few and many road points."""
    picks = []
    for motorway in (1, 0):
        subset = CELLS[CELLS["is_motorway"] == motorway].sort_values("road_points")
        for q in (0.1, 0.5, 0.9, 0.99):
            picks.append(subset.index[int(q * (len(subset) - 1))])
    return [h3.cell_to_latlng(c) for c in picks]


NAMED = [  # lat, lon of well-known stretches
    (39.3279, -120.1833),  # I-80 at Truckee
    (38.5816, -121.4944),  # Sacramento downtown
    (34.0522, -118.2437),  # Los Angeles downtown
    (35.3733, -119.0187),  # Bakersfield (CA-99)
    (36.7378, -119.7871),  # Fresno
    (37.6391, -120.9969),  # Modesto
    (32.7157, -117.1611),  # San Diego
    (40.5865, -122.3917),  # Redding (I-5)
]
OUTSIDE = [(36.5, -124.5), (35.9, -116.9)]  # Pacific Ocean; remote desert


def build_samples():
    points = known_cell_points() + NAMED + OUTSIDE  # 8 + 8 + 2 = 18
    points += [(39.3279, -120.1833), (37.6391, -120.9969)]  # repeats with other alerts/times -> 20
    etas = [
        "2026-01-15T16:00:00Z", "2026-03-08T09:30:00Z", "2026-03-08T10:30:00Z", "2026-07-04T00:15:00Z",
        "2026-11-01T08:30:00Z", "2026-11-01T09:30:00Z", "2026-12-31T23:59:00Z", "2027-02-01T13:45:00Z",
        "2026-05-11T07:00:00Z", "2026-08-16T19:20:00Z", "2026-10-12T08:00:00Z", "2026-10-12T15:00:00Z",
        "2026-06-21T12:00:00Z", "2026-09-30T06:59:00Z", "2026-04-05T22:10:00Z", "2026-02-28T03:00:00Z",
        "2026-10-18T11:00:00Z", "2026-12-07T18:30:00Z", "2027-01-03T05:45:00Z", "2026-10-12T20:30:00Z",
    ]
    samples = []
    for i, ((lat, lon), eta) in enumerate(zip(points, etas)):
        here, far = square(lat, lon), square(lat + 3, lon + 3)
        eta_dt = parse_time(eta)
        expired_at = (eta_dt - timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
        starts_later = (eta_dt + timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
        combos = [
            [],
            [alert("WS", "W", here)],
            [alert("WS", "A", here), alert("WI", "Y", here)],
            [alert("FF", "W", here), alert("FA", "Y", here), alert("FL", "W", here)],
            [alert("HT", "W", here), alert("EH", "A", here)],
            [alert("SV", "W", here), alert("TO", "A", here), alert("FG", "Y", here)],
            [alert("BZ", "W", here, expires=expired_at)],                # expired: ignored
            [alert("HW", "W", far)],                                      # does not cover: ignored
            [alert("IS", "W", here, starts=starts_later)],                # not started yet: ignored
            [alert("FW", "W", here), alert("SC", "Y", here)],             # SC: no group, still counted
            [alert("EW", "W", here), alert("DU", "Y", here), alert("ZR", "Y", here)],
            [{"phenom": None, "sig": None, "polygon": here,                # no VTEC: ignored
              "starts_at": None, "expires_at": None}],
        ]
        samples.append({"lat": float(lat), "lon": float(lon), "eta": eta, "alerts": combos[i % len(combos)]})
    return samples


# ----------------------------------------------------------------------------- route formula

def route_result(points):
    """points: list of per-point dicts (lat, lon, p_incident, major_share, place_rate, known)."""
    incident_km = base_km = delay = 0.0
    no_major = 1.0
    for a, b in zip(points[:-1], points[1:]):  # each point covers the segment to the next one
        km = haversine_km(a["lat"], a["lon"], b["lat"], b["lon"])
        rel_network = a["p_incident"] / META["overall_mean_p_2021"]
        e = ENCOUNTERS_PER_100KM * (km / 100) * rel_network
        incident_km += a["p_incident"] * km
        base_km += a["place_rate"] * km
        ms = a["major_share"]
        delay += e * (ms * MAJOR_INCIDENT_DELAY_MIN + (1 - ms) * MINOR_INCIDENT_DELAY_MIN)
        no_major *= 1 - min(1.0, e * ms)
    uncapped = delay
    delay = min(delay, MAX_INCIDENT_DELAY_MIN)
    unknown = sum(not p["known"] for p in points)
    return {
        "uncapped_incident_delay_min": uncapped,
        "low_confidence": unknown * 2 > len(points),  # more than half the points on unknown cells
        "expected_incident_delay_min": delay,
        "major_slowdown_probability": 1 - no_major,
        "incident_risk_ratio": incident_km / base_km,
        "risk_score": int(np.floor(100 * (1 - math.exp(-delay / RISK_SCORE_SCALE_MIN)) + 0.5)),  # Math.round
    }


def haversine_km(lat1, lon1, lat2, lon2):
    """Same formula and Earth radius as GeoUtil.haversineKm."""
    d_lat, d_lon = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = (math.sin(d_lat / 2) ** 2
         + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(d_lon / 2) ** 2)
    return 2 * 6371.0 * math.asin(math.sqrt(a))


# ----------------------------------------------------------------------------- main

def main():
    samples = build_samples()
    rows = []
    for s in samples:
        cell, known, vector, place_rate, cell_mean_p, _ = features_for(s["lat"], s["lon"], s["eta"], s["alerts"])
        rows.append((cell, known, vector, place_rate, cell_mean_p))

    X = np.array([r[2] for r in rows])
    inc_raw, inc_p = INCIDENT.predict(X, raw_score=True), INCIDENT.predict(X)
    maj_raw, maj_p = MAJOR.predict(X, raw_score=True), MAJOR.predict(X)

    cal, mcal = META["calibration"], META["major_calibration"]
    out_samples = []
    for s, (cell, known, vector, place_rate, cell_mean_p), ir, ip, mr, mp in zip(
            samples, rows, inc_raw, inc_p, maj_raw, maj_p):
        p_model = platt(ip, cal["a"], cal["b"])
        p_incident = place_rate * min(max(p_model / cell_mean_p, CAP_LOW), CAP_HIGH)
        out_samples.append({
            **s,
            "h3_cell": cell,
            "known_cell": bool(known),
            "features": vector,
            "incident_raw_score": float(ir),
            "incident_probability": float(ip),
            "major_raw_score": float(mr),
            "major_probability": float(mp),
            "place_rate_2021": place_rate,
            "cell_mean_p": cell_mean_p,
            "p_model": p_model,
            "p_incident": p_incident,
            "major_share": platt(mp, mcal["a"], mcal["b"]),
        })

    route_defs = {"north_valley": [14, 13, 12, 11], "mixed": [0, 1, 2, 3, 4, 5], "with_unknown": [15, 16, 17, 18],
                  "quiet_known": [19, 7, 6], "half_unknown": [8, 0, 18, 1]}
    routes = [{"name": name, "sample_indices": idx, "expected": route_result([
        {"lat": out_samples[i]["lat"], "lon": out_samples[i]["lon"], "p_incident": out_samples[i]["p_incident"],
         "major_share": out_samples[i]["major_share"], "place_rate": out_samples[i]["place_rate_2021"],
         "known": out_samples[i]["known_cell"]}
        for i in idx])} for name, idx in route_defs.items()]

    golden = {
        "model_version": META["model_version"],
        "generated_with": {"lightgbm": lgb.__version__, "h3": h3.__version__},
        "constants": {"encounters_per_100km": ENCOUNTERS_PER_100KM,
                      "minor_incident_delay_min": MINOR_INCIDENT_DELAY_MIN,
                      "major_incident_delay_min": MAJOR_INCIDENT_DELAY_MIN,
                      "max_incident_delay_min": MAX_INCIDENT_DELAY_MIN, "risk_score_scale_min": RISK_SCORE_SCALE_MIN},
        "samples": out_samples,
        "routes": routes,
    }
    (HERE / "golden.json").write_text(json.dumps(golden, indent=2) + "\n")
    known = sum(s["known_cell"] for s in out_samples)
    print(f"Wrote {len(out_samples)} samples ({known} known cells) and {len(routes)} routes to golden.json")
    for r in routes:
        print(r["name"], {k: (round(v, 4) if isinstance(v, float) else v) for k, v in r["expected"].items()})


if __name__ == "__main__":
    main()
