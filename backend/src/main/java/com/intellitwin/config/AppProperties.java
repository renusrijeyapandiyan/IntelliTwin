package com.intellitwin.config;

import com.intellitwin.core.DecisionConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed binding of the `intellitwin.*` properties. */
@ConfigurationProperties(prefix = "intellitwin")
public class AppProperties {
    private final Jwt jwt = new Jwt();
    private String ingestApiKey;
    private String corsOrigins = "";
    private final Ai ai = new Ai();
    private final Simulator simulator = new Simulator();
    private final Twin twin = new Twin();
    private final Retention retention = new Retention();
    private final Seed seed = new Seed();
    private final DecisionConfig decision = new DecisionConfig();

    @PostConstruct
    void validate() {
        if (jwt.secret == null || jwt.secret.length() < 32)
            throw new IllegalStateException("JWT_SECRET must be set and at least 32 characters long");
        if (ingestApiKey == null || ingestApiKey.length() < 16)
            throw new IllegalStateException("INGEST_API_KEY must be set and at least 16 characters long");
        if (ai.apiKey == null || ai.apiKey.isBlank()) throw new IllegalStateException("AI_API_KEY must be set");
        decision.validate();
    }

    public static class Jwt {
        private String secret;
        private long expirationMinutes = 480;
        public String getSecret() { return secret; }
        public void setSecret(String s) { secret = s; }
        public long getExpirationMinutes() { return expirationMinutes; }
        public void setExpirationMinutes(long v) { expirationMinutes = v; }
    }

    public static class Ai {
        private String url;
        private String apiKey;
        private int timeoutMs = 2000;
        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { apiKey = v; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int v) { timeoutMs = v; }
    }

    public static class Simulator {
        private String url;
        private String controlToken = "";
        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        public String getControlToken() { return controlToken; }
        public void setControlToken(String v) { controlToken = v; }
    }

    public static class Twin {
        private int staleAfterSeconds = 10;
        private int historySize = 300;
        private int warmupSamples = 120;
        private int maxFutureSeconds = 60;
        public int getStaleAfterSeconds() { return staleAfterSeconds; }
        public void setStaleAfterSeconds(int v) { staleAfterSeconds = v; }
        public int getHistorySize() { return historySize; }
        public void setHistorySize(int v) { historySize = v; }
        public int getWarmupSamples() { return warmupSamples; }
        public void setWarmupSamples(int v) { warmupSamples = v; }
        public int getMaxFutureSeconds() { return maxFutureSeconds; }
        public void setMaxFutureSeconds(int v) { maxFutureSeconds = v; }
    }

    public static class Retention {
        private int sensorDays = 3;
        private int predictionDays = 7;
        public int getSensorDays() { return sensorDays; }
        public void setSensorDays(int v) { sensorDays = v; }
        public int getPredictionDays() { return predictionDays; }
        public void setPredictionDays(int v) { predictionDays = v; }
    }

    public static class Seed {
        private String adminPassword = "";
        private String engineerPassword = "";
        private String operatorPassword = "";
        public String getAdminPassword() { return adminPassword; }
        public void setAdminPassword(String v) { adminPassword = v; }
        public String getEngineerPassword() { return engineerPassword; }
        public void setEngineerPassword(String v) { engineerPassword = v; }
        public String getOperatorPassword() { return operatorPassword; }
        public void setOperatorPassword(String v) { operatorPassword = v; }
    }

    public Jwt getJwt() { return jwt; }
    public String getIngestApiKey() { return ingestApiKey; }
    public void setIngestApiKey(String v) { ingestApiKey = v; }
    public String getCorsOrigins() { return corsOrigins; }
    public void setCorsOrigins(String v) { corsOrigins = v; }
    public Ai getAi() { return ai; }
    public Simulator getSimulator() { return simulator; }
    public Twin getTwin() { return twin; }
    public Retention getRetention() { return retention; }
    public Seed getSeed() { return seed; }
    public DecisionConfig getDecision() { return decision; }
}
