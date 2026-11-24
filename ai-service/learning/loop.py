"""Self-learning loop.  python -m learning.loop --once   |   python -m learning.loop  (every LEARN_INTERVAL_SECONDS)

Cycle:  1) a SHADOW candidate exists  -> evaluate it on labelled live data and record the result
        2) otherwise                  -> drift check (KS/PSI) + feedback performance
                                         drift or degraded performance -> train candidate -> start shadow mode
Promotion is NEVER automatic: an ADMIN must call POST /api/models/{id}/promote.
"""
from __future__ import annotations
import argparse
import json
import logging
import os
import time
from datetime import datetime, timedelta, timezone

import numpy as np
import pandas as pd

from app import simpath  # noqa: F401
from app.features import FEATURE_NAMES
from app.registry import Registry
from learning.drift import drift_report
from learning.feedback import feedback_degraded, feedback_performance, label_predictions
from learning.retrain import train_candidate
from learning.shadow import evaluate_shadow
from learning.sources import MongoSource

log = logging.getLogger("intellitwin.learning")
MIN_CANDIDATE_ROWS = 400


def cycle(reg: Registry, src: MongoSource, lookback_hours: float = 6, force: bool = False) -> dict:
    now = datetime.now(timezone.utc)
    since = now - timedelta(hours=lookback_hours)
    state = reg.state()
    preds = src.predictions(since)
    events = src.maintenance(since - timedelta(hours=1))
    if preds.empty:
        return {"action": "none", "reason": "no recent predictions"}
    labelled = label_predictions(preds, events)

    # ---------- 1) shadow evaluation ----------
    sh = state["shadow"]
    if sh:
        bundle_a, bundle_s = reg.load_bundle(state["active"]), reg.load_bundle(sh)
        df = labelled[labelled["shadowVersion"] == sh].rename(columns={"p_shadow": "p_shadow"})
        df = df.dropna(subset=["p_shadow"])
        res = evaluate_shadow(df, bundle_a["threshold"], bundle_s["threshold"])
        res["evaluatedAt"] = now.isoformat()
        reg.annotate(sh, shadowEvaluation=res)
        log.info("shadow %s evaluation: %s", sh, json.dumps({k: v for k, v in res.items() if k != "active" and k != "shadow"}))
        return {"action": "shadow_evaluated", "version": sh, "evaluation": res}

    # ---------- 2) drift + feedback ----------
    active = state["active"]
    ref = reg.load_bundle(active)["reference"]
    healthy_recent = preds[preds["recommendation"] == "CONTINUE_MONITORING"]
    drift = drift_report(ref, healthy_recent, FEATURE_NAMES)
    perf = feedback_performance(preds, events)
    degraded = feedback_degraded(perf)
    reg.annotate(active, driftReport={**{k: v for k, v in drift.items() if k != "features"}, "feedback": perf, "checkedAt": now.isoformat()})
    log.info("drift_detected=%s share=%s feedback=%s", drift["drift_detected"], drift.get("share_drifted"), perf)
    if not (drift["drift_detected"] or degraded or force):
        return {"action": "none", "drift": drift["drift_detected"], "feedback_degraded": degraded}

    recent = labelled.dropna(subset=["y"])
    # assumed-healthy: quiet windows with no feedback at all are weak negatives (documented label noise)
    quiet = labelled[labelled["y"].isna() & (labelled["recommendation"] == "CONTINUE_MONITORING")].sample(frac=1.0, random_state=0).head(2000).assign(y=0.0)
    recent = pd.concat([recent, quiet]).assign(t=lambda d: d["ts"].astype("int64") // 10**9).sort_values("t")
    if len(recent) < MIN_CANDIDATE_ROWS or recent["y"].nunique() < 2:
        return {"action": "none", "reason": f"not enough labelled recent data ({len(recent)} rows, classes={recent['y'].nunique()})"}
    trigger = {"drift": drift["drift_detected"], "feedback_degraded": degraded, "top_psi": drift.get("top_psi", {})}
    out = train_candidate(reg, recent[["t", "y"] + FEATURE_NAMES], trigger=trigger)
    reg.set_shadow(out["version"])
    log.info("candidate %s trained and placed in SHADOW mode", out["version"])
    return {"action": "candidate_trained", **out}


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    ap = argparse.ArgumentParser()
    ap.add_argument("--registry", default=os.getenv("MODEL_REGISTRY_DIR", "model_registry"))
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--force", action="store_true", help="train a candidate even without drift (demo)")
    ap.add_argument("--interval", type=int, default=int(os.getenv("LEARN_INTERVAL_SECONDS", "600")))
    ap.add_argument("--lookback-hours", type=float, default=float(os.getenv("LEARN_LOOKBACK_HOURS", "6")))
    a = ap.parse_args()
    reg = Registry(a.registry)
    while True:
        try:
            if reg.active is None:
                log.info("waiting for an active model ...")
            else:
                res = cycle(reg, MongoSource(), a.lookback_hours, a.force)
                log.info("cycle result: %s", {k: v for k, v in res.items() if k not in ("candidate", "active", "evaluation")})
        except Exception:
            log.exception("learning cycle failed")
        if a.once:
            break
        time.sleep(a.interval)


if __name__ == "__main__":
    main()
