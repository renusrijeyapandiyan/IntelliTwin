package com.intellitwin.core;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** D(t+1) = F(D(t), X(t)): pure, deterministic twin update. */
public final class TwinUpdater {
    public record Prediction(String modelVersion, double anomalyScore, double failureProbability, double confidence,
                             double inferenceMs) {}

    public record Outcome(TwinState state, boolean applied, String note) {}

    private TwinUpdater() {}

    /**
     * @param prev      previous twin state (null for a brand-new twin)
     * @param quality   validation quality of reading r
     * @param pred      model output, or null when no prediction was made
     * @param dec       decision for that prediction, or null
     * @param features  raw window features (null if none)
     * @param aiStatus  status to record when pred == null
     */
    public static Outcome apply(TwinState prev, Reading r, Quality quality, Prediction pred, DecisionEngine.Result dec,
                                Map<String, Double> features, Map<String, Double> baselines, String aiStatus,
                                Instant now, int historySize) {
        if (prev != null && prev.lastSensorTs != null && !r.ts().isAfter(prev.lastSensorTs)) {
            return new Outcome(prev, false, "OUT_OF_ORDER");   // old/duplicate-time record: ignored
        }
        TwinState s = prev == null ? new TwinState() : prev.copy();
        s.id = r.machineId();
        s.machineId = r.machineId();
        s.lastSensorTs = r.ts();
        s.lastUpdate = now;
        s.syncStatus = "SYNCED";
        s.machineState = r.state();
        s.dataQuality = quality.name();
        s.updateCount++;

        if (quality != Quality.BAD) {
            Map<String, Double> latest = new LinkedHashMap<>();
            for (String k : Sensors.NAMES) latest.put(k, r.value(k));
            s.latest = latest;
        }
        double lat = Math.max(0, Duration.between(r.ts(), now).toMillis());
        s.latencyMs = lat;
        s.latencyAvgMs = s.latencyAvgMs == null ? lat : 0.9 * s.latencyAvgMs + 0.1 * lat;

        if (pred != null && dec != null) {
            s.aiStatus = "OK";
            s.modelVersion = pred.modelVersion();
            s.anomalyScore = pred.anomalyScore();
            s.failureProbability = pred.failureProbability();
            s.riskScore = dec.risk();
            s.trend = dec.trend();
            s.recommendation = dec.recommendation().name();
            s.reasons = new java.util.ArrayList<>(dec.reasons());
            s.lowConfidence = dec.lowConfidence();
            s.lowConfidenceReasons = new java.util.ArrayList<>(dec.lowConfidenceReasons());
            if (features != null) {
                s.features = new LinkedHashMap<>(features);
                if (baselines != null) s.healthIndicators = HealthIndicators.compute(features, baselines);
            }
        } else {
            s.aiStatus = aiStatus;
            if ("NOT_RUNNING".equals(aiStatus)) {
                s.reasons = new java.util.ArrayList<>(java.util.List.of("machine is " + r.state().toLowerCase() + "; risk is not evaluated"));
            }
        }

        if (quality != Quality.BAD) {
            TwinState.HistoryPoint h = new TwinState.HistoryPoint();
            h.ts = r.ts();
            h.temperature = r.temperature(); h.vibration = r.vibration(); h.pressure = r.pressure();
            h.current = r.current(); h.speed = r.speed();
            h.risk = s.riskScore; h.failureProbability = s.failureProbability; h.anomalyScore = s.anomalyScore;
            h.recommendation = s.recommendation;
            s.history.add(h);
            while (s.history.size() > Math.max(1, historySize)) s.history.remove(0);   // bounded history
        }
        return new Outcome(s, true, "APPLIED");
    }

    /** Marks the twin STALE when no update arrived for `after`. Returns true if the status changed. */
    public static boolean markStale(TwinState s, Instant now, Duration after) {
        if (s.lastUpdate == null || "STALE".equals(s.syncStatus)) return false;
        if (Duration.between(s.lastUpdate, now).compareTo(after) > 0) {
            s.syncStatus = "STALE";
            return true;
        }
        return false;
    }
}
