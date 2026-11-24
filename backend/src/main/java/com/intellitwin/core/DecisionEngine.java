package com.intellitwin.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Composite risk  R = (w1*p + w2*anomaly + w3*trend) / (w1+w2+w3), smoothed with an EMA, mapped to an
 * advisory recommendation with hysteresis. Pure logic: no I/O, no machine control.
 */
public final class DecisionEngine {
    public record Input(double failureProbability, double anomalyScore, double confidence,
                        List<TopFeature> topFeatures, Quality quality) {}

    public record Result(double rawRisk, double risk, double trend, Recommendation recommendation,
                         List<String> reasons, boolean lowConfidence, List<String> lowConfidenceReasons) {}

    private final DecisionConfig cfg;

    public DecisionEngine(DecisionConfig cfg) {
        cfg.validate();
        this.cfg = cfg;
    }

    public DecisionConfig config() { return cfg; }

    /** Rise of p over the recent window (least-squares fit), scaled to 0..1; falling or flat -> 0. */
    public double trend(List<Double> recentP, double currentP) {
        List<Double> series = new ArrayList<>();
        int keep = Math.max(0, cfg.getTrendWindow() - 1);
        int from = Math.max(0, recentP.size() - keep);
        series.addAll(recentP.subList(from, recentP.size()));
        series.add(currentP);
        int n = series.size();
        if (n < 5) return 0.0;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = series.get(i);
        double rise = FeatureExtractor.slope(x) * (n - 1);
        return clamp01(rise / cfg.getTrendScale());
    }

    /** Mean of the current probability and up to (probabilitySmoothing-1) previous ones: damps single-sample jitter. */
    public double smoothedP(List<Double> recentP, double currentP) {
        int keep = Math.max(0, cfg.getProbabilitySmoothing() - 1);
        int from = Math.max(0, recentP.size() - keep);
        double sum = currentP;
        for (int i = from; i < recentP.size(); i++) sum += recentP.get(i);
        return sum / (recentP.size() - from + 1);
    }

    public Recommendation map(double risk, Recommendation previous) {
        Recommendation r;
        if (risk >= cfg.getImmediateThreshold()) r = Recommendation.IMMEDIATE_INTERVENTION;
        else if (risk >= cfg.getScheduleThreshold()) r = Recommendation.SCHEDULE_MAINTENANCE;
        else if (risk >= cfg.getInspectThreshold()) r = Recommendation.INSPECT_MACHINE;
        else r = Recommendation.CONTINUE_MONITORING;
        // hysteresis: only step down once risk is clearly below the previous level's threshold
        if (previous != null && previous.rank() > r.rank() && risk >= cfg.threshold(previous) - cfg.getHysteresis()) {
            return previous;
        }
        return r;
    }

    public Result decide(Input in, List<Double> recentP, Double prevRisk, Recommendation prevRec) {
        double trend = trend(recentP, in.failureProbability());
        double wSum = cfg.getProbabilityWeight() + cfg.getAnomalyWeight() + cfg.getTrendWeight();
        double p = smoothedP(recentP, in.failureProbability());
        double raw = clamp01((cfg.getProbabilityWeight() * p + cfg.getAnomalyWeight() * in.anomalyScore()
                + cfg.getTrendWeight() * trend) / wSum);
        double risk = prevRisk == null ? raw : cfg.getEmaAlpha() * raw + (1 - cfg.getEmaAlpha()) * prevRisk;
        Recommendation rec = map(risk, prevRec);

        List<String> low = new ArrayList<>();
        if (in.confidence() < cfg.getMinConfidence()) low.add("failure probability is close to the decision boundary");
        if (in.quality() != Quality.OK) low.add("input data quality is " + in.quality());
        if (Math.abs(in.failureProbability() - in.anomalyScore()) > cfg.getDisagreement())
            low.add("failure model and anomaly detector disagree");
        List<String> reasons = ReasonBuilder.build(in.topFeatures(), trend, cfg.getReasonMinZ(), 3);
        return new Result(raw, risk, trend, rec, reasons, !low.isEmpty(), low);
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
