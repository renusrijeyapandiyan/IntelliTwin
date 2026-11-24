package com.intellitwin.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TwinUpdaterTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Map<String, Double> BASE = Map.of("temperature", 55.0, "vibration", 2.8, "pressure", 6.5,
            "current", 22.0, "speed", 2950.0);

    private static Reading at(int sec, double vib) {
        return new Reading("M-002", T0.plusSeconds(sec), (long) sec, 55, vib, 6.5, 22, 2950, "RUNNING");
    }

    private static Map<String, Double> feats() {
        return new java.util.LinkedHashMap<>(Map.ofEntries(
                Map.entry("vibration_rms", 4.2), Map.entry("temperature_mean", 60.0), Map.entry("current_mean", 22.0),
                Map.entry("pressure_mean", 6.5), Map.entry("speed_mean", 2950.0), Map.entry("vibration_crest", 1.4)));
    }

    private static DecisionEngine.Result dec(double risk, Recommendation r) {
        return new DecisionEngine.Result(risk, risk, 0.1, r, List.of("vibration RMS 50% above baseline"), false, List.of());
    }

    private static TwinUpdater.Prediction pred(double p) {
        return new TwinUpdater.Prediction("v0001", 0.4, p, Math.abs(2 * p - 1), 3.0);
    }

    @Test
    void createsTwinOnFirstReadingAndStoresPrediction() {
        var o = TwinUpdater.apply(null, at(1, 2.8), Quality.OK, pred(0.7), dec(0.5, Recommendation.SCHEDULE_MAINTENANCE),
                feats(), BASE, null, T0.plusSeconds(2), 100);
        assertTrue(o.applied());
        var s = o.state();
        assertEquals("M-002", s.machineId);
        assertEquals("SYNCED", s.syncStatus);
        assertEquals("OK", s.aiStatus);
        assertEquals("SCHEDULE_MAINTENANCE", s.recommendation);
        assertEquals(0.7, s.failureProbability, 1e-12);
        assertEquals("v0001", s.modelVersion);
        assertEquals(2950.0, s.latest.get("speed"), 1e-12);
        assertEquals(1.5, s.healthIndicators.get("vibrationRmsRatio"), 1e-12);   // 4.2 / 2.8
        assertEquals(1, s.history.size());
    }

    @Test
    void outOfOrderAndDuplicateTimestampsAreIgnored() {
        var first = TwinUpdater.apply(null, at(10, 2.8), Quality.OK, null, null, null, BASE, "WARMING_UP", T0.plusSeconds(11), 100).state();
        var old = TwinUpdater.apply(first, at(5, 99), Quality.OK, null, null, null, BASE, "WARMING_UP", T0.plusSeconds(12), 100);
        assertFalse(old.applied());
        assertSame(first, old.state());
        assertEquals(10, first.lastSensorTs.getEpochSecond() - T0.getEpochSecond());
        var same = TwinUpdater.apply(first, at(10, 2.9), Quality.OK, null, null, null, BASE, "WARMING_UP", T0.plusSeconds(12), 100);
        assertFalse(same.applied());
        var newer = TwinUpdater.apply(first, at(11, 2.9), Quality.OK, null, null, null, BASE, "WARMING_UP", T0.plusSeconds(12), 100);
        assertTrue(newer.applied());
        assertEquals(2, newer.state().updateCount);
    }

    @Test
    void historyIsBounded() {
        TwinState s = null;
        for (int i = 1; i <= 25; i++) {
            s = TwinUpdater.apply(s, at(i, 2.8), Quality.OK, null, null, null, BASE, "WARMING_UP", T0.plusSeconds(i), 10).state();
        }
        assertEquals(10, s.history.size());
        assertEquals(T0.plusSeconds(16), s.history.get(0).ts);    // oldest 15 dropped
        assertEquals(T0.plusSeconds(25), s.history.get(9).ts);
    }

    @Test
    void badQualityDoesNotOverwriteLatestValuesOrHistory() {
        var ok = TwinUpdater.apply(null, at(1, 2.8), Quality.OK, null, null, null, BASE, "WARMING_UP", T0.plusSeconds(1), 10).state();
        var bad = TwinUpdater.apply(ok, at(2, 49.0), Quality.BAD, null, null, null, BASE, "BAD_DATA", T0.plusSeconds(2), 10).state();
        assertEquals(2.8, bad.latest.get("vibration"), 1e-12);
        assertEquals("BAD", bad.dataQuality);
        assertEquals("BAD_DATA", bad.aiStatus);
        assertEquals(1, bad.history.size());
    }

    @Test
    void staleDetectionAndRecovery() {
        var s = TwinUpdater.apply(null, at(1, 2.8), Quality.OK, null, null, null, BASE, "WARMING_UP", T0.plusSeconds(1), 10).state();
        assertFalse(TwinUpdater.markStale(s, T0.plusSeconds(5), Duration.ofSeconds(10)));
        assertEquals("SYNCED", s.syncStatus);
        assertTrue(TwinUpdater.markStale(s, T0.plusSeconds(20), Duration.ofSeconds(10)));
        assertEquals("STALE", s.syncStatus);
        assertFalse(TwinUpdater.markStale(s, T0.plusSeconds(30), Duration.ofSeconds(10)));   // already stale
        var back = TwinUpdater.apply(s, at(21, 2.8), Quality.OK, null, null, null, BASE, "WARMING_UP", T0.plusSeconds(21), 10).state();
        assertEquals("SYNCED", back.syncStatus);
    }

    @Test
    void measuresSensorToTwinLatency() {
        var s = TwinUpdater.apply(null, at(1, 2.8), Quality.OK, null, null, null, BASE, "WARMING_UP", T0.plusMillis(1250), 10).state();
        assertEquals(250.0, s.latencyMs, 1e-9);
        var s2 = TwinUpdater.apply(s, at(2, 2.8), Quality.OK, null, null, null, BASE, "WARMING_UP", T0.plusMillis(2450), 10).state();
        assertEquals(450.0, s2.latencyMs, 1e-9);
        assertEquals(0.9 * 250 + 0.1 * 450, s2.latencyAvgMs, 1e-9);
    }

    @Test
    void updateDoesNotMutatePreviousState() {
        var a = TwinUpdater.apply(null, at(1, 2.8), Quality.OK, null, null, null, BASE, "WARMING_UP", T0.plusSeconds(1), 10).state();
        int h = a.history.size();
        TwinUpdater.apply(a, at(2, 2.8), Quality.OK, null, null, null, BASE, "WARMING_UP", T0.plusSeconds(2), 10);
        assertEquals(h, a.history.size());
        assertEquals(1, a.updateCount);
    }
}
