package com.intellitwin.core;

import java.time.Instant;

/** A validated, complete reading (missing values already imputed). */
public record Reading(String machineId, Instant ts, Long seq,
                      double temperature, double vibration, double pressure, double current, double speed,
                      String state) {
    public double value(String sensor) {
        return switch (sensor) {
            case "temperature" -> temperature;
            case "vibration" -> vibration;
            case "pressure" -> pressure;
            case "current" -> current;
            case "speed" -> speed;
            default -> throw new IllegalArgumentException(sensor);
        };
    }

    public boolean running() { return "RUNNING".equals(state); }
}
