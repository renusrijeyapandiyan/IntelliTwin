package com.intellitwin.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Digital twin document D(t): persisted in the `twins` collection (id = machineId). */
public class TwinState {
    public String id;
    public String machineId;
    public Instant lastSensorTs;          // event time of the newest accepted reading
    public Instant lastUpdate;            // wall-clock time of the last twin update
    public String syncStatus = "SYNCED";  // SYNCED | STALE
    public String machineState;           // RUNNING | IDLE | STARTUP | STOPPED
    public String dataQuality = "OK";     // quality of the last reading
    public String aiStatus = "WARMING_UP";// OK | WARMING_UP | NOT_RUNNING | AI_UNAVAILABLE | BAD_DATA
    public Map<String, Double> latest = new LinkedHashMap<>();
    public Map<String, Double> features = new LinkedHashMap<>();
    public Map<String, Double> healthIndicators = new LinkedHashMap<>();
    public Double anomalyScore;
    public Double failureProbability;
    public Double riskScore;
    public Double trend;
    public String recommendation = Recommendation.CONTINUE_MONITORING.name();
    public List<String> reasons = new ArrayList<>();
    public boolean lowConfidence;
    public List<String> lowConfidenceReasons = new ArrayList<>();
    public String modelVersion;
    public Double latencyMs;              // sensor timestamp -> twin update
    public Double latencyAvgMs;           // EMA
    public int alertRank = 0;             // highest recommendation rank already alerted (hysteresis for alerts)
    public long updateCount;
    public List<HistoryPoint> history = new ArrayList<>();

    public static class HistoryPoint {
        public Instant ts;
        public Double temperature, vibration, pressure, current, speed;
        public Double risk, failureProbability, anomalyScore;
        public String recommendation;
    }

    public TwinState copy() {
        TwinState c = new TwinState();
        c.id = id; c.machineId = machineId; c.lastSensorTs = lastSensorTs; c.lastUpdate = lastUpdate;
        c.syncStatus = syncStatus; c.machineState = machineState; c.dataQuality = dataQuality; c.aiStatus = aiStatus;
        c.latest = new LinkedHashMap<>(latest); c.features = new LinkedHashMap<>(features);
        c.healthIndicators = new LinkedHashMap<>(healthIndicators);
        c.anomalyScore = anomalyScore; c.failureProbability = failureProbability; c.riskScore = riskScore; c.trend = trend;
        c.recommendation = recommendation; c.reasons = new ArrayList<>(reasons); c.lowConfidence = lowConfidence;
        c.lowConfidenceReasons = new ArrayList<>(lowConfidenceReasons); c.modelVersion = modelVersion;
        c.latencyMs = latencyMs; c.latencyAvgMs = latencyAvgMs; c.alertRank = alertRank; c.updateCount = updateCount;
        c.history = new ArrayList<>(history);
        return c;
    }
}
