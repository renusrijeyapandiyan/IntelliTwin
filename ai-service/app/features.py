"""Rolling-window feature extraction (the single source of truth for the feature spec).

The Java backend (FeatureExtractor) implements the SAME definitions; both are checked against
shared/feature_golden.json by their unit tests so training and serving cannot drift apart.

Window: the last WINDOW valid readings in state RUNNING (start-up / stopped readings are excluded).
Per sensor: mean, std (population, ddof=0), min, max, slope (least squares per sample, x = 0..n-1).
Vibration extras: rms = sqrt(mean(v^2)), crest = max(|v|)/rms.
"""
from __future__ import annotations
import math
from typing import Dict, Iterable, List, Mapping

import numpy as np

SENSORS = ["temperature", "vibration", "pressure", "current", "speed"]
STATS = ["mean", "std", "min", "max", "slope"]
WINDOW = 30
MIN_SAMPLES = 10
WARMUP_SAMPLES = 120   # consecutive RUNNING readings required before predictions are made (thermal warm-up)

FEATURE_NAMES: List[str] = [f"{s}_{st}" for s in SENSORS for st in STATS] + ["vibration_rms", "vibration_crest"]


def _slope(x: np.ndarray) -> float:
    n = len(x)
    if n < 2:
        return 0.0
    t = np.arange(n, dtype=float)
    tc = t - t.mean()
    return float(np.dot(tc, x - x.mean()) / np.dot(tc, tc))


def compute_features(readings: Iterable[Mapping[str, float]]) -> Dict[str, float]:
    """readings: oldest->newest list of dicts with the 5 sensor keys. Uses the last WINDOW."""
    rows = list(readings)[-WINDOW:]
    if len(rows) < MIN_SAMPLES:
        raise ValueError(f"need at least {MIN_SAMPLES} readings, got {len(rows)}")
    out: Dict[str, float] = {}
    for s in SENSORS:
        x = np.array([float(r[s]) for r in rows], dtype=float)
        out[f"{s}_mean"] = float(x.mean())
        out[f"{s}_std"] = float(x.std())          # ddof = 0
        out[f"{s}_min"] = float(x.min())
        out[f"{s}_max"] = float(x.max())
        out[f"{s}_slope"] = _slope(x)
    v = np.array([float(r["vibration"]) for r in rows], dtype=float)
    rms = float(math.sqrt(float(np.mean(v ** 2))))
    out["vibration_rms"] = rms
    out["vibration_crest"] = float(np.max(np.abs(v)) / rms) if rms > 0 else 0.0
    return out


def normalize(features: Mapping[str, float], baselines: Mapping[str, float]) -> Dict[str, float]:
    """Make features machine-independent by dividing by the machine's baseline mean.

    mean/std/min/max/rms -> x / baseline;  slope -> relative drift over one window;  crest unchanged.
    """
    out: Dict[str, float] = {}
    for s in SENSORS:
        b = float(baselines[s])
        if b <= 0:
            raise ValueError(f"baseline for {s} must be > 0")
        for st in ("mean", "std", "min", "max"):
            out[f"{s}_{st}"] = float(features[f"{s}_{st}"]) / b
        out[f"{s}_slope"] = float(features[f"{s}_slope"]) * WINDOW / b
    out["vibration_rms"] = float(features["vibration_rms"]) / float(baselines["vibration"])
    out["vibration_crest"] = float(features["vibration_crest"])
    return {k: out[k] for k in FEATURE_NAMES}


def to_vector(norm: Mapping[str, float]) -> np.ndarray:
    return np.array([float(norm[k]) for k in FEATURE_NAMES], dtype=float)
