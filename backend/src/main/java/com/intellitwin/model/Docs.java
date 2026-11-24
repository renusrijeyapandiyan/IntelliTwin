package com.intellitwin.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MongoDB documents (plain POJOs, persisted through MongoTemplate with explicit collection names). */
public final class Docs {
    private Docs() {}

    public static final String USERS = "users";
    public static final String MACHINES = "machines";
    public static final String READINGS = "sensor_readings";
    public static final String TWINS = "twins";
    public static final String PREDICTIONS = "predictions";
    public static final String ALERTS = "alerts";
    public static final String MAINTENANCE = "maintenance_records";
    public static final String MODELS = "model_versions";
    public static final String AUDIT = "audit_log";

    public static class User {
        public String id;
        public String username;          // unique
        public String passwordHash;      // BCrypt
        public String role;              // ADMIN | ENGINEER | OPERATOR
        public Instant createdAt;
    }

    public static class Machine {
        public String id;                // = machineId
        public String machineId;
        public String name;
        public String type;
        public String location;
        public Map<String, Map<String, Double>> baselines = new LinkedHashMap<>();   // sensor -> {mean, std}
        public Map<String, Map<String, Double>> thresholds = new LinkedHashMap<>();  // sensor -> {min, max} physical limits
        public Instant createdAt;

        public Map<String, Double> baselineMeans() {
            Map<String, Double> m = new LinkedHashMap<>();
            baselines.forEach((k, v) -> m.put(k, v.get("mean")));
            return m;
        }
    }

    public static class SensorReading {
        public String id;
        public String machineId;
        public Instant ts;               // event time
        public Instant receivedAt;
        public Long seq;
        public Double temperature, vibration, pressure, current, speed;
        public String state;
        public String quality;           // OK | SUSPECT | BAD
        public List<String> qualityFlags = new ArrayList<>();
        public Instant createdAt;        // TTL anchor
    }

    public static class PredictionRecord {
        public String id;
        public String machineId;
        public Instant ts;               // reading event time
        public Instant createdAt;
        public String modelVersion;
        public Double anomalyScore, failureProbability, confidence, risk, trend;
        public String recommendation;
        public boolean lowConfidence;
        public List<String> reasons = new ArrayList<>();
        public List<Map<String, Object>> topFeatures = new ArrayList<>();
        public Map<String, Double> features = new LinkedHashMap<>();   // raw window features (used by the learning loop)
        public String quality;
        public Double inferenceMs;       // model inference time reported by the AI service
        public Double aiCallMs;          // HTTP round trip to the AI service
        public Double endToEndMs;        // sensor timestamp -> twin updated
        public Map<String, Object> shadow;   // shadow-mode candidate output, if any
    }

    public static class Alert {
        public String id;
        public String machineId;
        public String type;              // RISK | STALE
        public String recommendation;    // for RISK alerts
        public Double riskScore;
        public List<String> reasons = new ArrayList<>();
        public boolean lowConfidence;
        public String message;
        public String status = "OPEN";   // OPEN | ACKNOWLEDGED
        public Instant createdAt;
        public String ackBy;
        public Instant ackAt;
    }

    public static class MaintenanceRecord {
        public String id;
        public String machineId;
        public String action;
        public String notes;
        public String outcome;           // FAULT_CONFIRMED | NO_FAULT_FOUND | PREVENTIVE | OTHER
        public String performedBy;
        public Instant performedAt;
        public Instant createdAt;
    }

    public static class ModelVersion {
        public String id;                // = version, e.g. v0002
        public String modelId;
        public String version;
        public String status;            // ACTIVE | CANDIDATE | SHADOW | ARCHIVED | ROLLED_BACK
        public String createdAt;
        public String parent;
        public Map<String, Object> metrics = new LinkedHashMap<>();
        public Map<String, Object> trainingDataSummary = new LinkedHashMap<>();
        public Map<String, Object> shadowEvaluation;
        public Map<String, Object> driftReport;
        public String approvedBy;
        public String promotedAt;
        public String note;
    }

    public static class AuditEntry {
        public String id;
        public String user;
        public String action;
        public String target;
        public String details;
        public Instant ts;
    }
}
