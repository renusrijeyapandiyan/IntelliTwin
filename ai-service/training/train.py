"""Train IsolationForest (anomaly) + RandomForest (failure probability) and print REAL metrics.

    python -m training.train --seed 42 --cycles 24 --activate

Evaluation protocol: per-machine chronological split 60/20/20 (train/val/test) with a purge gap of one
window at each boundary so overlapping windows never straddle splits. Thresholds are chosen on validation
only. Test data is used once, for the final report. Results are on SIMULATED data.
"""
from __future__ import annotations
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Optional, Tuple

import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest, RandomForestClassifier
from sklearn.metrics import f1_score, precision_score, recall_score, roc_auc_score

from app.features import FEATURE_NAMES, WINDOW
from app.registry import Registry
from training.make_dataset import make_dataset

GAP_ROWS = 7  # >= WINDOW/stride rows purged at each split boundary (30/5 = 6, +1)


# ------------------------------------------------------------------ data
def chronological_split(df: pd.DataFrame, train=0.6, val=0.2, gap: int = GAP_ROWS) -> Tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    parts = {"train": [], "val": [], "test": []}
    for _, g in df.groupby("machineId", sort=False):
        g = g.sort_values("t")
        n = len(g)
        a, b = int(n * train), int(n * (train + val))
        parts["train"].append(g.iloc[: a - gap])
        parts["val"].append(g.iloc[a:b - gap])
        parts["test"].append(g.iloc[b:])
    return tuple(pd.concat(parts[k], ignore_index=True) for k in ("train", "val", "test"))  # type: ignore


# ------------------------------------------------------------------ models
def fit_models(train: pd.DataFrame, val: pd.DataFrame, seed: int = 42) -> Dict:
    X = train[FEATURE_NAMES].to_numpy()
    y = train["y"].to_numpy()
    healthy = X[y == 0]
    iso = IsolationForest(n_estimators=200, contamination="auto", random_state=seed, n_jobs=-1).fit(healthy)
    rf = RandomForestClassifier(n_estimators=250, max_depth=14, min_samples_leaf=3, class_weight="balanced",
                                random_state=seed, n_jobs=-1).fit(X, y)

    a_train = -iso.score_samples(X)
    lo = float(np.percentile(a_train[y == 0], 50))                      # typical healthy -> 0
    severe = train["severity"].to_numpy() >= 0.9
    hi = float(np.percentile(a_train[severe], 90)) if severe.any() else float(a_train.max())   # severe fault -> ~1
    if hi <= lo:
        hi = lo + 1e-6
    bundle = {"iforest": iso, "rf": rf, "anomaly_lo": lo, "anomaly_hi": hi}
    p_val = rf.predict_proba(val[FEATURE_NAMES].to_numpy())[:, 1]
    thr = best_f1_threshold(val["y"].to_numpy(), p_val)
    bundle["threshold"] = thr
    return bundle


def anomaly_norm(raw: np.ndarray, lo: float, hi: float) -> np.ndarray:
    return np.clip((raw - lo) / (hi - lo), 0.0, 1.0)


def best_f1_threshold(y: np.ndarray, p: np.ndarray) -> float:
    best, best_t = -1.0, 0.5
    for t in np.linspace(0.05, 0.95, 91):
        f = f1_score(y, (p >= t).astype(int), zero_division=0)
        if f > best:
            best, best_t = f, float(t)
    return best_t


def metrics(y: np.ndarray, score: np.ndarray, pred: np.ndarray) -> Dict[str, Optional[float]]:
    out = {"precision": float(precision_score(y, pred, zero_division=0)),
           "recall": float(recall_score(y, pred, zero_division=0)),
           "f1": float(f1_score(y, pred, zero_division=0)),
           "roc_auc": float(roc_auc_score(y, score)) if len(np.unique(y)) == 2 else None}
    return out


def threshold_baseline(df: pd.DataFrame) -> np.ndarray:
    """Fixed alarm rules on baseline-normalised means (no fitting): the 'simple threshold' reference."""
    return ((df["temperature_mean"] > 1.15) | (df["vibration_mean"] > 1.5) | (df["pressure_mean"] < 0.8) |
            (df["current_mean"] > 1.3) | (df["speed_mean"] < 0.85)).astype(int).to_numpy()


def evaluate(bundle: Dict, df: pd.DataFrame, thr: Optional[float] = None) -> Dict:
    X = df[FEATURE_NAMES].to_numpy()
    y = df["y"].to_numpy()
    thr = bundle["threshold"] if thr is None else thr
    p = bundle["rf"].predict_proba(X)[:, 1]
    a = anomaly_norm(-bundle["iforest"].score_samples(X), bundle["anomaly_lo"], bundle["anomaly_hi"])
    res = {
        "n_samples": int(len(df)), "n_positive": int(y.sum()),
        "random_forest": metrics(y, p, (p >= thr).astype(int)),
        "isolation_forest": metrics(y, a, (a >= 0.5).astype(int)),
        "threshold_baseline": metrics(y, threshold_baseline(df).astype(float), threshold_baseline(df)),
    }
    pred = (p >= thr).astype(int)
    res["recall_by_fault"] = {f: float(pred[(df["fault"] == f).to_numpy() & (y == 1)].mean())
                              for f in sorted(df["fault"].unique()) if f != "none" and ((df["fault"] == f) & (df["y"] == 1)).any()}
    return res


def reference_sample(train: pd.DataFrame, n: int = 2000, seed: int = 0) -> pd.DataFrame:
    """Healthy training rows kept with the model as the reference distribution for drift detection."""
    h = train[train["y"] == 0][FEATURE_NAMES]
    return h.sample(min(n, len(h)), random_state=seed).reset_index(drop=True)


def build_meta(bundle: Dict, train: pd.DataFrame, extra: Optional[Dict] = None) -> Dict:
    healthy = train[train["y"] == 0][FEATURE_NAMES]
    return {
        "features": FEATURE_NAMES, "window": WINDOW,
        "healthy_mean": healthy.mean().to_dict(), "healthy_std": healthy.std().replace(0, 1e-6).to_dict(),
        "importances": dict(zip(FEATURE_NAMES, map(float, bundle["rf"].feature_importances_))),
        "anomaly_lo": bundle["anomaly_lo"], "anomaly_hi": bundle["anomaly_hi"],
        "decision_threshold": bundle["threshold"],
        "label_definition": "y=1 when injected fault severity >= 0.30 (simulated ground truth)",
        "train_summary": {"rows": int(len(train)), "positives": int(train["y"].sum()),
                          "machines": sorted(train["machineId"].unique().tolist())},
        **(extra or {}),
    }


def print_report(res: Dict, title: str):
    print(f"\n=== {title} ===  (n={res['n_samples']}, positives={res['n_positive']})")
    print(f"{'model':<22}{'precision':>10}{'recall':>10}{'F1':>10}{'ROC-AUC':>10}")
    for k, name in (("threshold_baseline", "threshold baseline"), ("isolation_forest", "isolation forest"),
                    ("random_forest", "random forest")):
        m = res[k]
        auc = "n/a" if m["roc_auc"] is None else f"{m['roc_auc']:.4f}"
        print(f"{name:<22}{m['precision']:>10.4f}{m['recall']:>10.4f}{m['f1']:>10.4f}{auc:>10}")
    print("RF recall by fault:", {k: round(v, 3) for k, v in res["recall_by_fault"].items()})


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--cycles", type=int, default=24)
    ap.add_argument("--registry", default="model_registry")
    ap.add_argument("--activate", action="store_true", help="make this version ACTIVE (default for the first version)")
    ap.add_argument("--dataset", default=None, help="optional CSV from make_dataset instead of regenerating")
    a = ap.parse_args(argv)

    df = pd.read_csv(a.dataset) if a.dataset else make_dataset(a.seed, a.cycles)
    train, val, test = chronological_split(df)
    print(f"dataset rows={len(df)}  train={len(train)} val={len(val)} test={len(test)}  positives={int(df.y.sum())}")
    bundle = fit_models(train, val, a.seed)
    val_res, test_res = evaluate(bundle, val), evaluate(bundle, test)
    print(f"decision threshold (chosen on validation) = {bundle['threshold']:.2f}")
    print_report(val_res, "VALIDATION")
    print_report(test_res, "TEST (held-out, chronologically last)")
    print("\nNOTE: metrics are on simulator-generated data and say nothing about real equipment.")

    reg = Registry(a.registry)
    meta = build_meta(bundle, train, {"seed": a.seed, "cycles": a.cycles, "kind": "initial",
                                      "trainedAt": datetime.now(timezone.utc).isoformat(timespec="seconds")})
    bundle["reference"] = reference_sample(train, seed=a.seed)
    version = reg.save_version({k: bundle[k] for k in ("iforest", "rf", "anomaly_lo", "anomaly_hi", "threshold", "reference")}, meta,
                               {"validation": val_res, "test": test_res}, activate=a.activate)
    print(f"\nsaved model {version} -> {Path(a.registry) / version}  (status: {reg.state()['versions'][version]['status']})")
    return version


if __name__ == "__main__":
    main()
