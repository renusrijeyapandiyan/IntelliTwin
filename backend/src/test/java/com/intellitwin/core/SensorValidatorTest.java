package com.intellitwin.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SensorValidatorTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:10Z");

    private static RawReading raw(String id, String ts, Double t, Double v, Double p, Double c, Double s, String st) {
        return new RawReading(id, ts, 1L, t, v, p, c, s, st);
    }

    private static RawReading good() {
        return raw("M-001", "2026-01-01T00:00:09Z", 60.0, 2.0, 3.0, 35.0, 1480.0, "RUNNING");
    }

    private static Reading last() {
        return new Reading("M-001", Instant.parse("2026-01-01T00:00:08Z"), 0L, 61, 2.1, 3.1, 34, 1479, "RUNNING");
    }

    @Test
    void validReadingIsOk() {
        var r = SensorValidator.validate(good(), null, NOW, null, Duration.ofSeconds(60));
        assertFalse(r.rejected());
        assertEquals(Quality.OK, r.quality());
        assertTrue(r.flags().isEmpty());
        assertEquals(1480.0, r.cleaned().speed(), 1e-9);
    }

    @Test
    void schemaErrorsAreRejected() {
        assertTrue(SensorValidator.validate(raw(null, "2026-01-01T00:00:09Z", 1., 1., 1., 1., 1., "RUNNING"), null, NOW, null, null).rejected());
        assertTrue(SensorValidator.validate(raw("bad id!", "2026-01-01T00:00:09Z", 1., 1., 1., 1., 1., "RUNNING"), null, NOW, null, null).rejected());
        assertTrue(SensorValidator.validate(raw("M-1", "yesterday", 1., 1., 1., 1., 1., "RUNNING"), null, NOW, null, null).rejected());
        assertTrue(SensorValidator.validate(raw("M-1", "2026-01-01T00:00:09Z", 1., 1., 1., 1., 1., "EXPLODING"), null, NOW, null, null).rejected());
        assertTrue(SensorValidator.validate(null, null, NOW, null, null).rejected());
    }

    @Test
    void missingValueIsImputedAndSuspect() {
        var r = SensorValidator.validate(raw("M-001", "2026-01-01T00:00:09Z", null, 2.0, 3.0, 35.0, 1480.0, "RUNNING"),
                null, NOW, last(), Duration.ofSeconds(60));
        assertEquals(Quality.SUSPECT, r.quality());
        assertTrue(r.flags().contains("MISSING_TEMPERATURE"));
        assertEquals(61.0, r.cleaned().temperature(), 1e-9);   // forward-filled
    }

    @Test
    void tooManyMissingOrNoHistoryIsBad() {
        var many = SensorValidator.validate(raw("M-001", "2026-01-01T00:00:09Z", null, null, null, 35.0, 1480.0, "RUNNING"),
                null, NOW, last(), null);
        assertEquals(Quality.BAD, many.quality());
        var noHistory = SensorValidator.validate(raw("M-001", "2026-01-01T00:00:09Z", null, 2.0, 3.0, 35.0, 1480.0, "RUNNING"),
                null, NOW, null, null);
        assertEquals(Quality.BAD, noHistory.quality());
    }

    @Test
    void nanAndInfinityCountAsMissing() {
        var r = SensorValidator.validate(raw("M-001", "2026-01-01T00:00:09Z", Double.NaN, 2.0, 3.0, 35.0, Double.POSITIVE_INFINITY, "RUNNING"),
                null, NOW, last(), null);
        assertTrue(r.flags().contains("MISSING_TEMPERATURE") && r.flags().contains("MISSING_SPEED"));
    }

    @Test
    void outOfPhysicalRangeIsBad() {
        var r = SensorValidator.validate(raw("M-001", "2026-01-01T00:00:09Z", 9999.0, 2.0, 3.0, 35.0, 1480.0, "RUNNING"),
                null, NOW, last(), null);
        assertEquals(Quality.BAD, r.quality());
        assertTrue(r.flags().contains("OUT_OF_RANGE_TEMPERATURE"));
        var neg = SensorValidator.validate(raw("M-001", "2026-01-01T00:00:09Z", 60.0, -1.0, 3.0, 35.0, 1480.0, "RUNNING"),
                null, NOW, last(), null);
        assertEquals(Quality.BAD, neg.quality());
    }

    @Test
    void futureTimestampIsBad() {
        var r = SensorValidator.validate(raw("M-001", "2026-01-01T01:00:00Z", 60.0, 2.0, 3.0, 35.0, 1480.0, "RUNNING"),
                null, NOW, last(), Duration.ofSeconds(60));
        assertEquals(Quality.BAD, r.quality());
        assertTrue(r.flags().contains("FUTURE_TIMESTAMP"));
    }

    @Test
    void parsesOffsetTimestamps() {
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), SensorValidator.parseTs("2026-01-01T05:30:00+05:30"));
        assertNull(SensorValidator.parseTs(""));
    }
}
