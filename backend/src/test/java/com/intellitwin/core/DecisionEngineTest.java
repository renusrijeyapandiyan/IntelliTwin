package com.intellitwin.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecisionEngineTest {
    private final DecisionEngine engine = new DecisionEngine(new DecisionConfig());
    private static final List<TopFeature> NONE = List.of();

    private DecisionEngine.Input in(double p, double a, Quality q) {
        return new DecisionEngine.Input(p, a, Math.abs(2 * p - 1), NONE, q);
    }

    @Test
    void compositeRiskFollowsConfiguredWeights() {
        DecisionConfig c = new DecisionConfig();
        c.setProbabilityWeight(0.5); c.setAnomalyWeight(0.3); c.setTrendWeight(0.2);
        var e = new DecisionEngine(c);
        var res = e.decide(in(0.8, 0.5, Quality.OK), List.of(), null, null);
        assertEquals(0.5 * 0.8 + 0.3 * 0.5 + 0.2 * 0.0, res.rawRisk(), 1e-12);
        assertEquals(res.rawRisk(), res.risk(), 1e-12);   // no smoothing on the first sample
    }

    @Test
    void weightsAreNormalised() {
        DecisionConfig c = new DecisionConfig();
        c.setProbabilityWeight(1); c.setAnomalyWeight(1); c.setTrendWeight(0);
        var res = new DecisionEngine(c).decide(in(1.0, 1.0, Quality.OK), List.of(), null, null);
        assertEquals(1.0, res.rawRisk(), 1e-12);
    }

    @Test
    void mapsRiskToFourLevels() {
        assertEquals(Recommendation.CONTINUE_MONITORING, engine.map(0.10, null));
        assertEquals(Recommendation.INSPECT_MACHINE, engine.map(0.30, null));
        assertEquals(Recommendation.SCHEDULE_MAINTENANCE, engine.map(0.50, null));
        assertEquals(Recommendation.IMMEDIATE_INTERVENTION, engine.map(0.90, null));
    }

    @Test
    void thresholdBoundariesAreInclusive() {
        var c = engine.config();
        assertEquals(Recommendation.INSPECT_MACHINE, engine.map(c.getInspectThreshold(), null));
        assertEquals(Recommendation.SCHEDULE_MAINTENANCE, engine.map(c.getScheduleThreshold(), null));
        assertEquals(Recommendation.IMMEDIATE_INTERVENTION, engine.map(c.getImmediateThreshold(), null));
        assertEquals(Recommendation.CONTINUE_MONITORING, engine.map(c.getInspectThreshold() - 1e-9, null));
    }

    @Test
    void hysteresisPreventsFlappingOnStepDown() {
        var c = engine.config();
        double justBelow = c.getScheduleThreshold() - c.getHysteresis() / 2;
        assertEquals(Recommendation.SCHEDULE_MAINTENANCE, engine.map(justBelow, Recommendation.SCHEDULE_MAINTENANCE));
        double clearlyBelow = c.getScheduleThreshold() - c.getHysteresis() - 0.01;
        assertEquals(Recommendation.INSPECT_MACHINE, engine.map(clearlyBelow, Recommendation.SCHEDULE_MAINTENANCE));
        // stepping UP is never delayed
        assertEquals(Recommendation.IMMEDIATE_INTERVENTION, engine.map(0.9, Recommendation.INSPECT_MACHINE));
    }

    @Test
    void trendRisesWithIncreasingProbabilityAndIsZeroWhenFlatOrFalling() {
        List<Double> rising = new ArrayList<>(), flat = new ArrayList<>(), falling = new ArrayList<>();
        for (int i = 0; i < 29; i++) { rising.add(0.03 * i); flat.add(0.2); falling.add(0.9 - 0.01 * i); }
        assertTrue(engine.trend(rising, 0.87) > 0.4);
        assertEquals(0.0, engine.trend(flat, 0.2), 1e-9);
        assertEquals(0.0, engine.trend(falling, 0.61), 1e-12);
        assertEquals(0.0, engine.trend(List.of(0.1, 0.9), 0.95), 1e-12);   // too little history
    }

    @Test
    void probabilityIsSmoothedOverRecentPredictions() {
        List<Double> recent = List.of(0.0, 0.0, 0.0, 0.0);
        assertEquals(0.2, engine.smoothedP(recent, 1.0), 1e-12);             // (0+0+0+0+1)/5
        assertEquals(0.5, engine.smoothedP(List.of(0.0), 1.0), 1e-12);       // fewer than 5 samples available
        assertEquals(0.8, engine.smoothedP(List.of(0.0, 1.0, 1.0, 1.0, 1.0), 0.0), 1e-12);   // last 4 recent (all 1.0) + current 0.0
    }

    @Test
    void emaSmoothsRiskSpikes() {
        var first = engine.decide(in(0.0, 0.0, Quality.OK), List.of(), null, null);
        var spike = engine.decide(in(1.0, 1.0, Quality.OK), List.of(0.0), first.risk(), first.recommendation());
        assertTrue(spike.risk() < spike.rawRisk());
        double a = engine.config().getEmaAlpha();
        assertEquals(a * spike.rawRisk() + (1 - a) * first.risk(), spike.risk(), 1e-12);
    }

    @Test
    void lowConfidenceFlagsBoundaryBadQualityAndDisagreement() {
        assertTrue(engine.decide(in(0.5, 0.5, Quality.OK), List.of(), null, null).lowConfidence());     // boundary
        assertTrue(engine.decide(in(0.95, 0.9, Quality.SUSPECT), List.of(), null, null).lowConfidence()); // quality
        assertTrue(engine.decide(in(0.95, 0.1, Quality.OK), List.of(), null, null).lowConfidence());    // disagreement
        var ok = engine.decide(in(0.97, 0.9, Quality.OK), List.of(), null, null);
        assertFalse(ok.lowConfidence());
        assertTrue(ok.lowConfidenceReasons().isEmpty());
    }

    @Test
    void reasonsArePlainEnglishAndRespectMinZ() {
        var top = List.of(
                new TopFeature("vibration_rms", 1.40, 6.0, 0.2, 1.2, "HIGH"),
                new TopFeature("temperature_mean", 1.18, 4.0, 0.1, 0.4, "HIGH"),
                new TopFeature("pressure_mean", 0.99, 0.5, 0.1, 0.05, "LOW"),   // |z| < 2 -> not quoted
                new TopFeature("vibration_crest", 2.9, 5.0, 0.1, 0.5, "HIGH"));
        var res = engine.decide(new DecisionEngine.Input(0.9, 0.8, 0.8, top, Quality.OK), List.of(), null, null);
        assertTrue(res.reasons().contains("vibration RMS 40% above baseline"), res.reasons().toString());
        assertTrue(res.reasons().contains("temperature 18% above baseline"));
        assertEquals(3, res.reasons().size());
        assertFalse(res.reasons().stream().anyMatch(s -> s.contains("pressure")));
    }

    @Test
    void invalidConfigurationFailsFast() {
        DecisionConfig c = new DecisionConfig();
        c.setInspectThreshold(0.6);   // above schedule threshold
        assertThrows(IllegalStateException.class, () -> new DecisionEngine(c));
        DecisionConfig z = new DecisionConfig();
        z.setProbabilityWeight(0); z.setAnomalyWeight(0); z.setTrendWeight(0);
        assertThrows(IllegalStateException.class, () -> new DecisionEngine(z));
    }
}
