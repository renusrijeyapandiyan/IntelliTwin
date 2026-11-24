"""Drift detection: two-sample KS test + Population Stability Index, per feature."""
from __future__ import annotations
from typing import Dict, List, Sequence

import numpy as np
import pandas as pd
from scipy import stats

PSI_BINS = 10
EPS = 1e-6


def psi(expected: np.ndarray, actual: np.ndarray, bins: int = PSI_BINS) -> float:
    """PSI with quantile bins taken from the reference sample. <0.1 stable, 0.1-0.25 moderate, >0.25 major."""
    expected = np.asarray(expected, dtype=float)
    actual = np.asarray(actual, dtype=float)
    edges = np.unique(np.quantile(expected, np.linspace(0, 1, bins + 1)))
    if len(edges) < 3:                       # (almost) constant reference
        return 0.0
    edges[0], edges[-1] = -np.inf, np.inf
    e = np.histogram(expected, edges)[0] / len(expected)
    a = np.histogram(actual, edges)[0] / len(actual)
    e, a = np.clip(e, EPS, None), np.clip(a, EPS, None)
    return float(np.sum((a - e) * np.log(a / e)))


def drift_report(reference: pd.DataFrame, recent: pd.DataFrame, features: Sequence[str],
                 ks_alpha: float = 0.01, psi_threshold: float = 0.2, min_share: float = 0.2,
                 min_rows: int = 300) -> Dict:
    """A feature is 'drifted' when KS rejects equality (p < alpha) AND PSI >= threshold (non-trivial effect size).
    Drift is declared when at least `min_share` of the features drifted."""
    if len(recent) < min_rows:
        return {"drift_detected": False, "reason": f"insufficient recent data ({len(recent)} < {min_rows} rows)",
                "n_recent": int(len(recent)), "n_reference": int(len(reference)), "features": {}}
    per: Dict[str, Dict] = {}
    for f in features:
        ks = stats.ks_2samp(reference[f].to_numpy(), recent[f].to_numpy())
        p = psi(reference[f].to_numpy(), recent[f].to_numpy())
        per[f] = {"ks_stat": float(ks.statistic), "ks_p": float(ks.pvalue), "psi": p,
                  "drifted": bool(ks.pvalue < ks_alpha and p >= psi_threshold)}
    drifted: List[str] = [f for f, v in per.items() if v["drifted"]]
    share = len(drifted) / len(features)
    top = sorted(per, key=lambda f: per[f]["psi"], reverse=True)[:5]
    return {"drift_detected": share >= min_share, "share_drifted": share, "n_drifted": len(drifted),
            "drifted_features": drifted, "top_psi": {f: per[f]["psi"] for f in top},
            "thresholds": {"ks_alpha": ks_alpha, "psi": psi_threshold, "min_share": min_share},
            "n_recent": int(len(recent)), "n_reference": int(len(reference)), "features": per}
