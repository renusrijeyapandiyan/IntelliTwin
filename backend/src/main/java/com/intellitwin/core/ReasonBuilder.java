package com.intellitwin.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Turns top contributing features into plain-English reasons, e.g. "vibration RMS 40% above baseline". */
public final class ReasonBuilder {
    private ReasonBuilder() {}

    private static String label(String sensor) {
        return switch (sensor) {
            case "current" -> "motor current";
            default -> sensor;
        };
    }

    public static List<String> build(List<TopFeature> top, double trend, double minZ, int maxReasons) {
        List<String> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (TopFeature f : top) {
            if (out.size() >= maxReasons) break;
            if (Math.abs(f.zScore()) < minZ) continue;
            String text = describe(f);
            if (text == null) continue;
            String key = f.name().startsWith("vibration_") ? f.name() : f.name().substring(0, f.name().indexOf('_')) + "/" + kind(f.name());
            if (used.add(key)) out.add(text);
        }
        if (trend >= 0.3 && out.size() < maxReasons + 1) out.add("failure probability has been rising over recent readings");
        if (out.isEmpty()) out.add("no single sensor feature deviates strongly from its healthy baseline");
        return out;
    }

    private static String kind(String name) {
        String stat = name.substring(name.indexOf('_') + 1);
        return switch (stat) {
            case "mean", "min", "max" -> "level";
            default -> stat;
        };
    }

    static String describe(TopFeature f) {
        String name = f.name();
        double v = f.value();
        boolean high = "HIGH".equals(f.direction());
        if (name.equals("vibration_rms")) return pct("vibration RMS", v);
        if (name.equals("vibration_crest"))
            return high ? String.format(Locale.ROOT, "vibration crest factor %.1f (impulsive spikes typical of bearing damage)", v) : null;
        String sensor = name.substring(0, name.indexOf('_'));
        String stat = name.substring(name.indexOf('_') + 1);
        switch (stat) {
            case "mean": return pct(label(sensor), v);
            case "min": return pct(label(sensor) + " (lowest value in window)", v);
            case "max": return pct(label(sensor) + " (peak in window)", v);
            case "std": return high ? label(sensor) + " signal is unusually unstable" : null;
            case "slope": {
                long drift = Math.round(Math.abs(v) * 100);
                if (drift < 3) return null;
                return String.format(Locale.ROOT, "%s is %s (%d%% of baseline over the last %d s)", label(sensor),
                        v > 0 ? "rising" : "falling", drift, Sensors.WINDOW);
            }
            default: return null;
        }
    }

    private static String pct(String what, double normalised) {
        long p = Math.round(Math.abs(normalised - 1.0) * 100);
        if (p < 3) return null;
        return String.format(Locale.ROOT, "%s %d%% %s baseline", what, p, normalised >= 1.0 ? "above" : "below");
    }
}
