package com.intellitwin.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Human-meaningful indicators derived from window features and the machine baseline. */
public final class HealthIndicators {
    private HealthIndicators() {}

    public static Map<String, Double> compute(Map<String, Double> f, Map<String, Double> baselines) {
        Map<String, Double> h = new LinkedHashMap<>();
        h.put("vibrationRmsRatio", f.get("vibration_rms") / baselines.get("vibration"));
        h.put("temperatureDeltaC", f.get("temperature_mean") - baselines.get("temperature"));
        h.put("currentRatio", f.get("current_mean") / baselines.get("current"));
        h.put("pressureRatio", f.get("pressure_mean") / baselines.get("pressure"));
        h.put("speedRatio", f.get("speed_mean") / baselines.get("speed"));
        h.put("crestFactor", f.get("vibration_crest"));
        return h;
    }
}
