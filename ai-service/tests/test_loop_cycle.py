"""Integration test of learning.loop.cycle against an in-memory MongoDB (mongomock)."""
import shutil
from datetime import datetime, timedelta, timezone

import numpy as np
import pandas as pd
import pytest

from app import simpath  # noqa: F401
from app.features import FEATURE_NAMES, SENSORS, WINDOW
from app.registry import Registry
from intellitwin_sim.machines import PROFILES
from learning import loop
from training.make_dataset import make_dataset

DISTORT = {"vibration": (1.30, 0.0), "temperature": (1.0, 6.0), "current": (1.08, 0.0)}


def denormalise(row, base):
    """Invert app.features.normalize so the test can store RAW window features like the backend does."""
    raw = {}
    for s in SENSORS:
        for st in ("mean", "std", "min", "max"):
            raw[f"{s}_{st}"] = row[f"{s}_{st}"] * base[s]
        raw[f"{s}_slope"] = row[f"{s}_slope"] * base[s] / WINDOW
    raw["vibration_rms"] = row["vibration_rms"] * base["vibration"]
    raw["vibration_crest"] = row["vibration_crest"]
    return {k: float(raw[k]) for k in FEATURE_NAMES}


@pytest.fixture()
def world(trained_registry, tmp_path):
    mongomock = pytest.importorskip("mongomock")
    d = tmp_path / "reg"; shutil.copytree(trained_registry, d)
    reg = Registry(d)
    from learning.sources import MongoSource
    src = MongoSource.__new__(MongoSource)
    src.db = mongomock.MongoClient()["intellitwin"]
    prof = PROFILES["M-001"]
    base = prof.baseline_means()
    src.db["machines"].insert_one({"machineId": "M-001", "baselines": {s: {"mean": v, "std": 0.1} for s, v in base.items()}})
    df = make_dataset(seed=5, cycles=8, machines=["M-001"], distort=DISTORT).sort_values("t").reset_index(drop=True)
    now = datetime.now(timezone.utc)
    t_end = now - timedelta(minutes=5)
    ts = [t_end - timedelta(seconds=5 * (len(df) - i)) for i in range(len(df))]
    active_bundle = reg.load_bundle(reg.active)
    p = active_bundle["rf"].predict_proba(df[FEATURE_NAMES].to_numpy())[:, 1]
    docs = []
    for i, row in df.iterrows():
        docs.append({"machineId": "M-001", "ts": ts[i], "createdAt": ts[i], "features": denormalise(row, base),
                     "failureProbability": float(p[i]), "recommendation": "CONTINUE_MONITORING" if row["y"] == 0 else "SCHEDULE_MAINTENANCE",
                     "risk": 0.1, "modelVersion": reg.active})
    src.db["predictions"].insert_many(docs)
    events, in_run = [], False                       # maintenance feedback at the end of each degraded run / during healthy spells
    for i in range(len(df)):
        y = df.loc[i, "y"]
        if in_run and (y == 0 or i == len(df) - 1):
            events.append({"machineId": "M-001", "outcome": "FAULT_CONFIRMED", "performedAt": ts[i - 1]})
        in_run = y == 1
        if y == 0 and i % 300 == 0:
            events.append({"machineId": "M-001", "outcome": "NO_FAULT_FOUND", "performedAt": ts[i]})
    src.db["maintenance_records"].insert_many(events)
    return reg, src, df, docs


def test_cycle_detects_drift_trains_candidate_then_evaluates_shadow(world):
    reg, src, df, docs = world
    before = reg.active
    res = loop.cycle(reg, src, lookback_hours=6)
    assert res["action"] == "candidate_trained", res
    st = reg.state()
    assert st["active"] == before                                   # never auto-promoted
    assert st["shadow"] == res["version"] and st["versions"][res["version"]]["status"] == "SHADOW"
    assert st["versions"][before]["driftReport"]["drift_detected"] is True

    # live traffic now carries shadow outputs; the next cycle evaluates them
    cand = reg.load_bundle(res["version"])
    pc = cand["rf"].predict_proba(df[FEATURE_NAMES].to_numpy())[:, 1]
    for i, d in enumerate(docs):
        src.db["predictions"].update_one({"ts": d["ts"]}, {"$set": {"shadow": {"modelVersion": res["version"], "failureProbability": float(pc[i])}}})
    res2 = loop.cycle(reg, src, lookback_hours=6)
    assert res2["action"] == "shadow_evaluated" and res2["evaluation"]["n_scored"] > 0
    assert "shadowEvaluation" in reg.state()["versions"][res["version"]]
    assert reg.state()["active"] == before                          # still requires ADMIN approval


def test_cycle_without_drift_or_data_does_nothing(world):
    reg, src, df, docs = world
    src.db["predictions"].delete_many({})
    assert loop.cycle(reg, src)["action"] == "none"
