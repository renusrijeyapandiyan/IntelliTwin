"""Shadow-mode evaluation: the candidate scores live data in parallel but never influences decisions."""
from __future__ import annotations
from typing import Dict

import numpy as np
import pandas as pd
from sklearn.metrics import f1_score, precision_score, recall_score, roc_auc_score


def _m(y, p, thr):
    pred = (p >= thr).astype(int)
    auc = float(roc_auc_score(y, p)) if len(np.unique(y)) == 2 else None
    return {"precision": float(precision_score(y, pred, zero_division=0)), "recall": float(recall_score(y, pred, zero_division=0)),
            "f1": float(f1_score(y, pred, zero_division=0)), "roc_auc": auc}


def evaluate_shadow(df: pd.DataFrame, active_thr: float, shadow_thr: float, min_labelled: int = 200,
                    min_gain_f1: float = 0.0, max_auc_loss: float = 0.01) -> Dict:
    """df columns: p_active, p_shadow, y (NaN when unlabelled). Promotion is only *recommended*; an ADMIN decides."""
    out: Dict = {"n_scored": int(len(df))}
    if len(df):
        out["agreement"] = float(((df["p_active"] >= active_thr) == (df["p_shadow"] >= shadow_thr)).mean())
        out["mean_abs_dp"] = float((df["p_active"] - df["p_shadow"]).abs().mean())
    lab = df.dropna(subset=["y"])
    out["n_labelled"] = int(len(lab))
    if len(lab) < min_labelled or lab["y"].nunique() < 2:
        out.update(promotion_ready=False, reason=f"need >= {min_labelled} labelled samples with both classes (have {len(lab)})")
        return out
    y = lab["y"].astype(int).to_numpy()
    a, s = _m(y, lab["p_active"].to_numpy(), active_thr), _m(y, lab["p_shadow"].to_numpy(), shadow_thr)
    out.update(active=a, shadow=s)
    ok = s["f1"] >= a["f1"] + min_gain_f1 and (s["roc_auc"] or 0) >= (a["roc_auc"] or 0) - max_auc_loss
    out.update(promotion_ready=bool(ok), reason="candidate >= active on labelled shadow data" if ok
               else "candidate does not beat the active model on labelled shadow data")
    return out
