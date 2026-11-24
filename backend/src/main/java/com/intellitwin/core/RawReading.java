package com.intellitwin.core;

/** Reading exactly as received over HTTP (nullable fields so that missing values can be detected). */
public record RawReading(String machineId, String ts, Long seq,
                         Double temperature, Double vibration, Double pressure, Double current, Double speed,
                         String state) {
    public Double value(String sensor) {
        return switch (sensor) {
            case "temperature" -> temperature;
            case "vibration" -> vibration;
            case "pressure" -> pressure;
            case "current" -> current;
            case "speed" -> speed;
            default -> throw new IllegalArgumentException(sensor);
        };
    }
}
