package com.intellitwin.core;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Schema + physical-range validation and quality flagging.
 *
 * <ul>
 *   <li>Schema problems (bad id, bad/missing timestamp, unknown state) -> rejected (HTTP 400).</li>
 *   <li>Missing value (null/NaN/Inf) -> imputed from the previous reading, quality SUSPECT (BAD if more than 2 missing).</li>
 *   <li>Value outside the physical range -> flagged, imputed, quality BAD (excluded from model windows).</li>
 *   <li>Timestamp too far in the future -> quality BAD.</li>
 * </ul>
 */
public final class SensorValidator {
    public record Range(double min, double max) {
        public boolean contains(double v) { return v >= min && v <= max; }
    }

    public record Result(boolean rejected, List<String> errors, Quality quality, List<String> flags, Reading cleaned) {}

    public static final Set<String> STATES = Set.of("RUNNING", "IDLE", "STARTUP", "STOPPED");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

    /** Generic physical limits; a machine document may override them. */
    public static final Map<String, Range> DEFAULT_RANGES = defaultRanges();

    private static Map<String, Range> defaultRanges() {
        Map<String, Range> m = new LinkedHashMap<>();
        m.put("temperature", new Range(-40, 250));
        m.put("vibration", new Range(0, 50));
        m.put("pressure", new Range(-0.5, 50));
        m.put("current", new Range(0, 500));
        m.put("speed", new Range(0, 10000));
        return m;
    }

    private SensorValidator() {}

    public static Instant parseTs(String ts) {
        if (ts == null || ts.isBlank()) return null;
        try {
            return Instant.parse(ts);
        } catch (Exception e) {
            try {
                return OffsetDateTime.parse(ts).toInstant();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    public static Result validate(RawReading raw, Map<String, Range> ranges, Instant now, Reading last, Duration maxFuture) {
        List<String> errors = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        if (raw == null) return new Result(true, List.of("empty body"), Quality.BAD, flags, null);
        if (raw.machineId() == null || !ID.matcher(raw.machineId()).matches()) errors.add("machineId missing or invalid");
        Instant ts = parseTs(raw.ts());
        if (ts == null) errors.add("ts missing or not ISO-8601");
        if (raw.state() == null || !STATES.contains(raw.state())) errors.add("state must be one of " + STATES);
        if (!errors.isEmpty()) return new Result(true, errors, Quality.BAD, flags, null);

        Map<String, Range> r = ranges == null ? DEFAULT_RANGES : ranges;
        double[] vals = new double[Sensors.NAMES.size()];
        int missing = 0, invalid = 0, unimputable = 0;
        for (int i = 0; i < vals.length; i++) {
            String s = Sensors.NAMES.get(i);
            Double v = raw.value(s);
            boolean bad = false;
            if (v == null || v.isNaN() || v.isInfinite()) {
                flags.add("MISSING_" + s.toUpperCase());
                missing++;
                bad = true;
            } else if (!r.getOrDefault(s, DEFAULT_RANGES.get(s)).contains(v)) {
                flags.add("OUT_OF_RANGE_" + s.toUpperCase());
                invalid++;
                bad = true;
            }
            if (bad) {
                if (last != null) vals[i] = last.value(s);
                else { vals[i] = 0; unimputable++; }
            } else {
                vals[i] = v;
            }
        }
        Quality q = Quality.OK;
        if (missing > 0) q = Quality.SUSPECT;
        if (missing > 2 || invalid > 0 || unimputable > 0) q = Quality.BAD;
        if (now != null && maxFuture != null && ts.isAfter(now.plus(maxFuture))) {
            flags.add("FUTURE_TIMESTAMP");
            q = Quality.BAD;
        }
        Reading cleaned = new Reading(raw.machineId(), ts, raw.seq(), vals[0], vals[1], vals[2], vals[3], vals[4], raw.state());
        return new Result(false, List.of(), q, flags, cleaned);
    }
}
