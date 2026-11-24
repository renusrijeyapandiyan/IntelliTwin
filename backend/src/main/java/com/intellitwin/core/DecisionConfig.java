package com.intellitwin.core;

/** Configurable weights and thresholds (bound from application.yml: intellitwin.decision.*). */
public class DecisionConfig {
    private double probabilityWeight = 0.4;
    private double anomalyWeight = 0.4;
    private double trendWeight = 0.2;
    private double inspectThreshold = 0.30;
    private double scheduleThreshold = 0.50;
    private double immediateThreshold = 0.75;
    private double emaAlpha = 0.2;          // smoothing of the composite risk
    private double hysteresis = 0.04;       // margin required before stepping DOWN a level
    private int trendWindow = 30;           // number of recent predictions used for the trend
    private double trendScale = 1.5;        // a fitted rise of this much probability over the window => trend = 1
    private int probabilitySmoothing = 5;             // failure probability is averaged over this many recent predictions
    private double minConfidence = 0.2;     // |2p-1| below this => low confidence
    private double disagreement = 0.6;      // |p - anomaly| above this => low confidence
    private double reasonMinZ = 2.0;        // minimum |z| for a feature to be quoted as a reason

    public double getProbabilityWeight() { return probabilityWeight; }
    public void setProbabilityWeight(double v) { probabilityWeight = v; }
    public double getAnomalyWeight() { return anomalyWeight; }
    public void setAnomalyWeight(double v) { anomalyWeight = v; }
    public double getTrendWeight() { return trendWeight; }
    public void setTrendWeight(double v) { trendWeight = v; }
    public double getInspectThreshold() { return inspectThreshold; }
    public void setInspectThreshold(double v) { inspectThreshold = v; }
    public double getScheduleThreshold() { return scheduleThreshold; }
    public void setScheduleThreshold(double v) { scheduleThreshold = v; }
    public double getImmediateThreshold() { return immediateThreshold; }
    public void setImmediateThreshold(double v) { immediateThreshold = v; }
    public double getEmaAlpha() { return emaAlpha; }
    public void setEmaAlpha(double v) { emaAlpha = v; }
    public double getHysteresis() { return hysteresis; }
    public void setHysteresis(double v) { hysteresis = v; }
    public int getTrendWindow() { return trendWindow; }
    public void setTrendWindow(int v) { trendWindow = v; }
    public double getTrendScale() { return trendScale; }
    public void setTrendScale(double v) { trendScale = v; }
    public int getProbabilitySmoothing() { return probabilitySmoothing; }
    public void setProbabilitySmoothing(int v) { probabilitySmoothing = v; }
    public double getMinConfidence() { return minConfidence; }
    public void setMinConfidence(double v) { minConfidence = v; }
    public double getDisagreement() { return disagreement; }
    public void setDisagreement(double v) { disagreement = v; }
    public double getReasonMinZ() { return reasonMinZ; }
    public void setReasonMinZ(double v) { reasonMinZ = v; }

    public double threshold(Recommendation r) {
        return switch (r) {
            case CONTINUE_MONITORING -> 0.0;
            case INSPECT_MACHINE -> inspectThreshold;
            case SCHEDULE_MAINTENANCE -> scheduleThreshold;
            case IMMEDIATE_INTERVENTION -> immediateThreshold;
        };
    }

    /** Fails fast on a nonsensical configuration. */
    public void validate() {
        if (probabilityWeight < 0 || anomalyWeight < 0 || trendWeight < 0 || probabilityWeight + anomalyWeight + trendWeight <= 0)
            throw new IllegalStateException("decision weights must be >= 0 and not all zero");
        if (!(0 < inspectThreshold && inspectThreshold < scheduleThreshold && scheduleThreshold < immediateThreshold
                && immediateThreshold <= 1))
            throw new IllegalStateException("thresholds must satisfy 0 < inspect < schedule < immediate <= 1");
        if (probabilitySmoothing < 1) throw new IllegalStateException("probabilitySmoothing must be >= 1");
        if (emaAlpha <= 0 || emaAlpha > 1) throw new IllegalStateException("emaAlpha must be in (0,1]");
    }
}
