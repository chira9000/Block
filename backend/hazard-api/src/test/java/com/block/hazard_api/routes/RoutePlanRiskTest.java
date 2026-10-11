package com.block.hazard_api.routes;

import static org.junit.jupiter.api.Assertions.*;

import com.block.hazard_api.hazards.Hazard;
import com.block.hazard_api.hazards.HazardFetcher;
import com.block.hazard_api.hazards.HazardService;
import com.block.hazard_api.risk.RiskModelV2;
import com.block.hazard_api.risk.RiskService;
import com.block.hazard_api.scoring.CoefficientRouteScorer;
import com.block.hazard_api.weather.KeywordWeatherBucketMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The v2 risk model inside the route pipeline, with fake Google and NWS clients (no network). */
class RoutePlanRiskTest {

    private static final Instant DEPART = Instant.parse("2099-10-12T08:00:00Z");
    private static RiskModelV2 model;

    @BeforeAll
    static void loadModel() throws Exception {
        model = RiskModelV2.loadFromClasspath("/risk_v2/");
    }

    private static HazardService hazards(List<Hazard> list) {
        HazardFetcher fake = new HazardFetcher() {
            public String sourceName() { return "FAKE"; }
            public List<Hazard> fetch() { return list; }
        };
        return new HazardService(List.of(fake));
    }

    private static RoutePlanResponse plan(RiskService risk) {
        var svc = new RoutePlanService(new RoutePlanServiceTest.FakeRoutes(),
                new RoutePlanServiceTest.FakeForecasts(p -> true), new KeywordWeatherBucketMapper(),
                new CoefficientRouteScorer(), hazards(List.of(RoutePlanServiceTest.STORM)), risk);
        return svc.plan(new RoutePlanRequest("Fresno, CA", "Sacramento, CA", DEPART, "strawberries"));
    }

    private static RoutePlanResponse planWithoutRisk() {
        return new RoutePlanService(new RoutePlanServiceTest.FakeRoutes(),
                new RoutePlanServiceTest.FakeForecasts(p -> true), new KeywordWeatherBucketMapper(),
                new CoefficientRouteScorer(), hazards(List.of(RoutePlanServiceTest.STORM)))
                .plan(new RoutePlanRequest("Fresno, CA", "Sacramento, CA", DEPART, "strawberries"));
    }

    @Test
    void addsRiskFieldsAndIncidentDelayBeforeShelfLife() {
        RoutePlanResponse withRisk = plan(new RiskService(model));
        RoutePlanResponse without = planWithoutRisk();

        assertEquals(without.modelVersion(), withRisk.modelVersion()); // still the v1 shelf-life model
        assertEquals(without.routes().size(), withRisk.routes().size());
        for (RoutePlanResponse.PlannedRoute r : withRisk.routes()) {
            RoutePlanResponse.PlannedRoute base = without.routes().stream()
                    .filter(b -> b.routeId().equals(r.routeId())).findFirst().orElseThrow();

            assertEquals("risk-v2.1", r.riskModelVersion());
            assertNotNull(r.riskScore());
            assertTrue(r.riskScore() >= 0 && r.riskScore() <= 100);
            assertNotNull(r.incidentRiskRatio());
            assertNotNull(r.majorSlowdownProbability());
            assertNotNull(r.riskFlags());
            assertNotNull(r.riskReasons());
            assertNotNull(r.riskLowConfidence());
            assertTrue(r.expectedIncidentDelayMin() > 0, "a 2-3 hour drive has some incident risk");

            // Google's own estimate is unchanged; the incident delay is inside the total and the shelf life
            assertEquals(base.googleDurationMin(), r.googleDurationMin());
            assertEquals(base.predictedTotalDurationMin() + r.expectedIncidentDelayMin(),
                    r.predictedTotalDurationMin(), 0.11);
            assertTrue(r.shelfLifeRemainingPct() <= base.shelfLifeRemainingPct());
        }
        // Ranking is still by shelf life, best first
        for (int i = 1; i < withRisk.routes().size(); i++) {
            assertTrue(withRisk.routes().get(i - 1).shelfLifeRemainingPct()
                    >= withRisk.routes().get(i).shelfLifeRemainingPct());
            assertEquals(i + 1, withRisk.routes().get(i).rank());
        }
    }

    @Test
    void riskFailureLeavesRiskFieldsNullAndEverythingElseUnchanged() {
        RiskService broken = new RiskService(model) {
            @Override
            public RouteRisk assess(List<SampledPoint> points, List<Hazard> alerts) {
                throw new IllegalStateException("simulated risk model failure");
            }
        };
        RoutePlanResponse res = plan(broken);
        RoutePlanResponse without = planWithoutRisk();

        for (RoutePlanResponse.PlannedRoute r : res.routes()) {
            assertNull(r.riskScore());
            assertNull(r.incidentRiskRatio());
            assertNull(r.expectedIncidentDelayMin());
            assertNull(r.majorSlowdownProbability());
            assertNull(r.riskFlags());
            assertNull(r.riskReasons());
            assertNull(r.riskModelVersion());
            assertNull(r.riskLowConfidence());
        }
        assertEquals(without, res); // exactly today's response
    }

    @Test
    void missingModelBehavesLikeToday() {
        assertEquals(planWithoutRisk(), plan(new RiskService((RiskModelV2) null)));
    }
}
