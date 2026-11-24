package com.intellitwin.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rolling-window features. MUST stay identical to ai-service/app/features.py (checked by both test suites
 * against shared/feature_golden.json).  std is the population std (ddof = 0); slope is the least-squares
 * slope per sample with x = 0..n-1.
 */
public final class FeatureExtractor {
    private FeatureExtractor() {}

    public static final List<String> STATS = List.of("mean", "std", "min", "max", "slope");

    public static List<String> featureNames() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String s : Sensors.NAMES) for (String st : STATS) out.add(s + "_" + st);
        out.add("vibration_rms");
        out.add("vibration_crest");
        return out;
    }

    public static Map<String, Double> extract(List<Reading> readings) {
        int from = Math.max(0, readings.size() - Sensors.WINDOW);
        List<Reading> w = readings.subList(from, readings.size());
        int n = w.size();
        if (n < Sensors.MIN_SAMPLES)
            throw new IllegalArgumentException("need at least " + Sensors.MIN_SAMPLES + " readings, got " + n);
        Map<String, Double> out = new LinkedHashMap<>();
        for (String s : Sensors.NAMES) {
            double[] x = new double[n];
            for (int i = 0; i < n; i++) x[i] = w.get(i).value(s);
            double mean = mean(x);
            double var = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (double v : x) {
                var += (v - mean) * (v - mean);
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            out.put(s + "_mean", mean);
            out.put(s + "_std", Math.sqrt(var / n));
            out.put(s + "_min", min);
            out.put(s + "_max", max);
            out.put(s + "_slope", slope(x));
        }
        double sq = 0, peak = 0;
        for (Reading r : w) {
            sq += r.vibration() * r.vibration();
            peak = Math.max(peak, Math.abs(r.vibration()));
        }
        double rms = Math.sqrt(sq / n);
        out.put("vibration_rms", rms);
        out.put("vibration_crest", rms > 0 ? peak / rms : 0.0);
        return out;
    }

    static double mean(double[] x) {
        double s = 0;
        for (double v : x) s += v;
        return s / x.length;
    }

    /** Least-squares slope per sample. */
    public static double slope(double[] x) {
        int n = x.length;
        if (n < 2) return 0;
        double tm = (n - 1) / 2.0, xm = mean(x), num = 0, den = 0;
        for (int i = 0; i < n; i++) {
            num += (i - tm) * (x[i] - xm);
            den += (i - tm) * (i - tm);
        }
        return num / den;
    }
}
