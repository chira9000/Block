package com.block.hazard_api.risk;

import static org.junit.jupiter.api.Assertions.*;

import com.block.hazard_api.hazards.Hazard;
import com.block.hazard_api.hazards.HazardCategory;
import com.block.hazard_api.hazards.LatLng;
import com.block.hazard_api.routes.SampledPoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks the pure-Java risk model against golden.json, which make_golden.py computed with the
 * real lightgbm and h3 Python libraries.
 */
class RiskModelV2Test {

    static RiskModelV2 model;
    static Golden.File golden;

    @BeforeAll
    static void load() throws Exception {
        model = RiskModelV2.loadFromClasspath("/risk_v2/");
        golden = Golden.load();
    }

    @Test
    void goldenFileMatchesTheShippedModel() {
        assertEquals(model.meta().modelVersion(), golden.modelVersion());
        assertEquals(20, golden.samples().size());
        assertEquals(67, model.incidentModel().numTrees());
        assertEquals(50, model.majorModel().numTrees());
    }

    // ---------------------------------------------------------------- step 3: LightGBM in Java

    @Test
    void lightGbmMatchesPythonWithin1e9() {
        for (int i = 0; i < golden.samples().size(); i++) {
            Golden.Sample s = golden.samples().get(i);
            assertEquals(s.incidentRawScore(), model.incidentModel().predictRaw(s.features()), 1e-9, "incident raw " + i);
            assertEquals(s.incidentProbability(), model.incidentModel().predictProbability(s.features()), 1e-9,
                    "incident p " + i);
            assertEquals(s.majorRawScore(), model.majorModel().predictRaw(s.features()), 1e-9, "major raw " + i);
            assertEquals(s.majorProbability(), model.majorModel().predictProbability(s.features()), 1e-9,
                    "major p " + i);
        }
    }

    // ---------------------------------------------------------------- step 4: features

    @Test
    void featureVectorsMatchPythonExactly() {
        for (int i = 0; i < golden.samples().size(); i++) {
            Golden.Sample s = golden.samples().get(i);
            RiskModelV2.PointRisk p = model.point(s.lat(), s.lon(), s.etaInstant(), s.hazards());
            assertEquals(s.h3Cell(), p.h3Cell(), "h3 cell " + i);
            assertEquals(s.knownCell(), p.knownCell(), "known cell " + i);
            assertArrayEquals(s.features(), p.features(), 0.0, "features " + i);
        }
    }

    @Test
    void goldenCoversKnownAndUnknownCellsAndAlertCombos() {
        assertTrue(golden.samples().stream().anyMatch(s -> !s.knownCell()), "needs unknown cells");
        assertTrue(golden.samples().stream().anyMatch(s -> s.features()[8] >= 3), "needs 3 active alerts");
        assertTrue(golden.samples().stream().anyMatch(s -> s.features()[7] == 2), "needs a watch as max level");
    }

    // ---------------------------------------------------------------- step 6: per-point and route formulas

    @Test
    void pointFormulasMatchPython() {
        for (int i = 0; i < golden.samples().size(); i++) {
            Golden.Sample s = golden.samples().get(i);
            RiskModelV2.PointRisk p = model.point(s.lat(), s.lon(), s.etaInstant(), s.hazards());
            assertEquals(s.placeRate2021(), p.placeRate2021(), 0.0, "place rate " + i);
            assertEquals(s.cellMeanP(), p.cellMeanP(), 0.0, "cell mean p " + i);
            assertEquals(s.pModel(), p.pModel(), 1e-12, "p_model " + i);
            assertEquals(s.pIncident(), p.pIncident(), 1e-12, "p_incident " + i);
            assertEquals(s.majorShare(), p.majorShare(), 1e-12, "major share " + i);
        }
    }

    @Test
    void routeFormulaMatchesPython() {
        for (Golden.Route r : golden.routes()) {
            List<RiskModelV2.PointRisk> points = new ArrayList<>();
            for (int i : r.sampleIndices()) {
                Golden.Sample s = golden.samples().get(i);
                points.add(model.point(s.lat(), s.lon(), s.etaInstant(), s.hazards()));
            }
            RiskModelV2.RouteNumbers n = model.combine(points);
            assertEquals(r.expected().expectedIncidentDelayMin(), n.expectedIncidentDelayMin(), 1e-9, r.name());
            assertEquals(r.expected().majorSlowdownProbability(), n.majorSlowdownProbability(), 1e-9, r.name());
            assertEquals(r.expected().incidentRiskRatio(), n.incidentRiskRatio(), 1e-9, r.name());
            assertEquals(r.expected().riskScore(), n.riskScore(), r.name());
            assertEquals(r.expected().lowConfidence(), RiskModelV2.lowConfidence(points), r.name());
        }
    }

    @Test
    void goldenRoutesCoverTheCapAndTheHalfUnknownEdge() {
        for (Golden.Route r : golden.routes()) {
            assertEquals(Math.min(r.expected().uncappedIncidentDelayMin(), 120), r.expected().expectedIncidentDelayMin(),
                    0.0, r.name());
        }
        // Force the cap: 1,000 km at 1,000x the network-average incident rate
        double meanP = model.meta().overallMeanP2021();
        var a = new RiskModelV2.PointRisk(36.0, -120.0, "x", true, new double[0], 0, 0, meanP, meanP, 0,
                1000 * meanP, 0.5, List.of());
        var b = new RiskModelV2.PointRisk(45.0, -120.0, "y", true, new double[0], 0, 0, meanP, meanP, 0,
                1000 * meanP, 0.5, List.of());
        RiskModelV2.RouteNumbers capped = model.combine(List.of(a, b));
        assertEquals(120.0, capped.expectedIncidentDelayMin(), 0.0);
        assertEquals(100, capped.riskScore());
        // half_unknown: exactly 2 of 4 points unknown -> NOT low confidence (needs MORE than half)
        Golden.Route half = golden.routes().stream().filter(r -> r.name().equals("half_unknown")).findFirst().orElseThrow();
        long unknown = half.sampleIndices().stream().filter(i -> !golden.samples().get(i).knownCell()).count();
        assertEquals(2, unknown);
        assertFalse(half.expected().lowConfidence());
    }

    @Test
    void encounterFormulaByHand() {
        // One 100 km segment on an average road (p_incident = network mean), all minor incidents:
        // e = 0.013 * 1 * 1 = 0.013 encounters -> 0.13 min, risk = 1.3% -> 1, ratio = p/place_rate
        double meanP = model.meta().overallMeanP2021();
        var a = new RiskModelV2.PointRisk(36.0, -120.0, "x", true, new double[0], 0, 0, meanP, meanP, 0, meanP, 0.0,
                List.of());
        double lonFor100Km = 100 / (111.19492664455873 * Math.cos(Math.toRadians(36.0)));
        var b = new RiskModelV2.PointRisk(36.0, -120.0 + lonFor100Km, "y", true, new double[0], 0, 0, meanP, meanP, 0,
                meanP, 0.0, List.of());
        RiskModelV2.RouteNumbers n = model.combine(List.of(a, b));
        assertEquals(0.13, n.expectedIncidentDelayMin(), 0.001); // ~100 km along a parallel
        assertEquals(1.0, n.incidentRiskRatio(), 1e-12);
        assertEquals(0.0, n.majorSlowdownProbability(), 0.0);
        assertEquals(1, n.riskScore()); // 100 * 0.013 = 1.3 -> 1
    }

    @Test
    void riskScoreIsChanceOfAtLeastOneIncident() {
        // e = 0.013 per 100 km on an average road -> 1,000 km = 10 segments: 1 - 0.987^10 = 12.3% -> 12
        assertEquals(12, (int) Math.round(100 * (1 - Math.pow(1 - RiskModelV2.ENCOUNTERS_PER_100KM, 10))));
    }

    // ---------------------------------------------------------------- flags and the service

    @Test
    void flagsOnlyForClosureTypeWarnings() {
        assertEquals("winter_closure_or_chains", RiskModelV2.flagFor("WS", "W"));
        assertEquals("winter_closure_or_chains", RiskModelV2.flagFor("BZ", "W"));
        assertEquals("winter_closure_or_chains", RiskModelV2.flagFor("IS", "W"));
        assertEquals("flood_closure_risk", RiskModelV2.flagFor("FF", "W"));
        assertEquals("flood_closure_risk", RiskModelV2.flagFor("FL", "W"));
        assertEquals("flood_closure_risk", RiskModelV2.flagFor("FA", "W"));
        assertEquals("high_wind_tall_vehicles", RiskModelV2.flagFor("HW", "W"));
        assertEquals("high_wind_tall_vehicles", RiskModelV2.flagFor("EW", "W"));
        assertNull(RiskModelV2.flagFor("WS", "A")); // watch, not warning
        assertNull(RiskModelV2.flagFor("HT", "W")); // heat: no closure flag
        assertNull(RiskModelV2.flagFor(null, null));
    }

    @Test
    void serviceBuildsRouteRiskWithFlagsAndReasons() {
        // Two points on I-80 near Truckee under a Winter Storm Warning and a High Wind Warning.
        Instant eta = Instant.parse("2026-01-15T16:00:00Z");
        List<List<Double>> box = List.of(List.of(-121.0, 38.9), List.of(-119.5, 38.9), List.of(-119.5, 39.8),
                List.of(-121.0, 39.8), List.of(-121.0, 38.9));
        List<Hazard> alerts = List.of(
                new Hazard("a", "NWS", "Winter Storm Warning", HazardCategory.STORM, "WSW", 0.75, 39.3, -120.2, 60,
                        box, null, null, "WS", "W"),
                new Hazard("b", "NWS", "High Wind Warning", HazardCategory.STORM, "HWW", 0.75, 39.3, -120.2, 60,
                        box, null, null, "HW", "W"));
        List<SampledPoint> points = List.of(
                new SampledPoint(new LatLng(39.3279, -120.1833), eta),
                new SampledPoint(new LatLng(39.3200, -120.3500), eta.plusSeconds(15 * 60)));

        RiskService.RouteRisk r = new RiskService(model).assess(points, alerts);

        assertEquals("risk-v2.1", r.riskModelVersion());
        assertEquals(List.of("winter_closure_or_chains", "high_wind_tall_vehicles"), r.riskFlags());
        assertEquals(2, r.riskReasons().size());
        assertTrue(r.riskReasons().get(0).startsWith("Winter Storm Warning on the route"), r.riskReasons().get(0));
        assertTrue(r.riskScore() >= 0 && r.riskScore() <= 100);
        assertTrue(r.expectedIncidentDelayMin() >= 0);
        assertTrue(r.majorSlowdownProbability() >= 0 && r.majorSlowdownProbability() <= 1);
        assertNotNull(r.incidentRiskRatio());
    }

    @Test
    void lowConfidenceWhenAPointIsOnAnUnknownCell() {
        Instant eta = Instant.parse("2026-01-15T16:00:00Z");
        List<SampledPoint> ocean = List.of(new SampledPoint(new LatLng(36.5, -124.5), eta),
                new SampledPoint(new LatLng(36.6, -124.4), eta.plusSeconds(600)));
        RiskService.RouteRisk r = new RiskService(model).assess(ocean, List.of());
        assertTrue(r.riskLowConfidence());
        assertTrue(r.riskFlags().isEmpty());
    }

    @Test
    void serviceWithoutModelReturnsNull() {
        assertNull(new RiskService((RiskModelV2) null).assess(List.of(), List.of()));
    }
}
