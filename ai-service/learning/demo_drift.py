"""End-to-end demonstration of the self-learning loop on SIMULATED sensor drift (no database needed).

    python -m learning.demo_drift

Scenario: sensors are re-calibrated in the field (vibration reads 30% higher, temperature +6 degC, current +8%).
Healthy machines now look 'abnormal' to the model trained before the change.
All numbers printed are computed by this script.
"""
from __future__ import annotations
import argparse
import json
import tempfile

import numpy as np
import pandas as pd

from app import simpath  # noqa: F401
from app.features import FEATURE_NAMES
from app.registry import Registry
from learning.drift import drift_report
from learning.retrain import train_candidate
from learning.shadow import evaluate_shadow
from learning.sources import SimulatedSource
from training import train as T

DISTORT = {"vibration": (1.30, 0.0), "temperature": (1.0, 6.0), "current": (1.08, 0.0)}


def fmt(m):
    return " ".join(f"{k}={'n/a' if v is None else round(v, 3)}" for k, v in m.items())


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--registry", default=None, help="registry dir (default: temporary)")
    a = ap.parse_args(argv)
    reg_dir = a.registry or tempfile.mkdtemp(prefix="intellitwin_reg_")
    reg = Registry(reg_dir)
    if reg.active is None:
        print("== training initial model v0001 ==")
        T.main(["--seed", "42", "--cycles", "24", "--registry", reg_dir, "--activate"])
    active = reg.active
    ref = reg.load_bundle(active)["reference"]

    print(f"\n== simulating sensor drift: {DISTORT} ==")
    src = SimulatedSource(DISTORT, seed=123, cycles=10)
    stream = src.frame()
    healthy_recent = stream[stream["y"] == 0]
    d = drift_report(ref, healthy_recent, FEATURE_NAMES)
    print(f"drift_detected={d['drift_detected']}  drifted features: {d['n_drifted']}/{len(FEATURE_NAMES)}  "
          f"(KS p<0.01 and PSI>=0.2)\n  top PSI: { {k: round(v, 2) for k, v in d['top_psi'].items()} }")
    reg.annotate(active, driftReport={k: v for k, v in d.items() if k != "features"})

    print("\n== training candidate on recent + sampled historical data ==")
    recent = stream[["t", "y"] + FEATURE_NAMES]
    out = train_candidate(reg, recent, trigger={"drift": d["drift_detected"], "top_psi": d["top_psi"]})
    cand = out["version"]
    reg.set_shadow(cand)
    print(f"candidate {cand} registered (status {reg.state()['versions'][cand]['status']}); active stays {active}")
    for name in ("active", "candidate"):
        r = out[name]
        print(f"  holdout[{name:9}] RF: {fmt(r['random_forest'])}   threshold-baseline: {fmt(r['threshold_baseline'])}")

    print("\n== shadow-mode comparison on the newest live data (candidate never influences decisions) ==")
    ba, bs = reg.load_bundle(active), reg.load_bundle(cand)
    ordered = stream.sort_values("t", kind="stable").reset_index(drop=True)
    tail = ordered.iloc[int(len(ordered) * 0.7):]          # exactly the chronological holdout the candidate never saw
    X = tail[FEATURE_NAMES].to_numpy()
    df = pd.DataFrame({"p_active": ba["rf"].predict_proba(X)[:, 1], "p_shadow": bs["rf"].predict_proba(X)[:, 1], "y": tail["y"].to_numpy()})
    ev = evaluate_shadow(df, ba["threshold"], bs["threshold"])
    reg.annotate(cand, shadowEvaluation=ev)
    print(json.dumps({k: (round(v, 3) if isinstance(v, float) else v) for k, v in ev.items() if k not in ("active", "shadow")}, indent=2))
    for k in ("active", "shadow"):
        if k in ev:
            print(f"  {k:6}: {fmt(ev[k])}")
    print(f"\npromotion_ready={ev['promotion_ready']}  -> an ADMIN must still approve:  POST /api/models/{cand}/promote")
    print(f"registry: {reg_dir}")
    return ev


if __name__ == "__main__":
    main()
