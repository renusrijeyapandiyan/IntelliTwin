"""Train a candidate on recent + sampled historical data and register it (never activates it)."""
from __future__ import annotations
from typing import Dict, Optional

import numpy as np
import pandas as pd

from app import simpath  # noqa: F401
from app.features import FEATURE_NAMES
from app.registry import Registry
from training import train as T
from training.make_dataset import make_dataset


def build_training_frame(recent: pd.DataFrame, historical: pd.DataFrame, hist_fraction: float = 1.0,
                         seed: int = 0) -> pd.DataFrame:
    """recent: labelled rows (FEATURE_NAMES + y [+ t]); historical: simulator dataset (has severity, fault).
    Historical rows are sampled to `hist_fraction` x len(recent) so recent behaviour is not drowned out."""
    n_hist = int(min(len(historical), max(300, hist_fraction * len(recent))))
    hist = historical.sample(n_hist, random_state=seed)
    rec = recent.copy()
    if "severity" not in rec:
        rec["severity"] = rec["y"].astype(float)       # confirmed faults treated as severe for anomaly calibration
    if "fault" not in rec:
        rec["fault"] = np.where(rec["y"] == 1, "feedback", "none")
    if "machineId" not in rec:
        rec["machineId"] = "recent"
    cols = ["machineId", "t", "fault", "severity", "y"] + FEATURE_NAMES
    rec = rec.assign(t=rec["t"] if "t" in rec else np.arange(len(rec)))
    hist = hist.assign(t=hist["t"])
    return pd.concat([hist[cols], rec[cols]], ignore_index=True)


def train_candidate(reg: Registry, recent: pd.DataFrame, historical: Optional[pd.DataFrame] = None,
                    seed: int = 7, trigger: Optional[Dict] = None, holdout_frac: float = 0.3) -> Dict:
    """Chronological holdout of the *recent* data is used to compare candidate vs the active model."""
    active = reg.active
    if active is None:
        raise RuntimeError("no active model to derive a candidate from")
    if historical is None:
        historical = make_dataset(seed=seed, cycles=12)
    recent = recent.sort_values("t", kind="stable").reset_index(drop=True) if "t" in recent else recent
    cut = int(len(recent) * (1 - holdout_frac))
    rec_train, rec_hold = recent.iloc[:cut], recent.iloc[cut:]
    # chronological: fit on the earlier 80% of recent rows, pick the decision threshold on the later 20%
    vcut = int(len(rec_train) * 0.8)
    rec_fit, rec_val = rec_train.iloc[:vcut], rec_train.iloc[vcut:]
    hist_val = historical.sample(min(300, len(historical) // 4), random_state=seed)
    hist_fit = historical.drop(hist_val.index)
    frame = build_training_frame(rec_fit, hist_fit, seed=seed)
    val = build_training_frame(rec_val, hist_val, seed=seed)
    bundle = T.fit_models(frame, val, seed)
    hold = rec_hold.assign(severity=rec_hold["y"].astype(float), fault="none")
    cand_eval = T.evaluate(bundle, hold)
    active_bundle = reg.load_bundle(active)
    act_eval = T.evaluate(active_bundle, hold, thr=active_bundle["threshold"])
    bundle["reference"] = T.reference_sample(frame, seed=seed)
    meta = T.build_meta(bundle, frame, {"kind": "retrained", "seed": seed, "drift_trigger": trigger or {},
                                        "recent_rows": int(len(rec_fit)), "holdout_rows": int(len(rec_hold))})
    version = reg.save_version({k: bundle[k] for k in ("iforest", "rf", "anomaly_lo", "anomaly_hi", "threshold", "reference")},
                               meta, {"recent_holdout": {"candidate": cand_eval, "active": act_eval, "active_version": active}},
                               activate=False, parent=active)
    return {"version": version, "candidate": cand_eval, "active": act_eval}
