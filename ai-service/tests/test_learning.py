import shutil
from datetime import datetime, timedelta, timezone

import numpy as np
import pandas as pd
import pytest

from app.features import FEATURE_NAMES, compute_features
from app.registry import Registry
from learning.drift import drift_report, psi
from learning.feedback import feedback_degraded, feedback_performance, label_predictions
from learning.retrain import train_candidate
from learning.shadow import evaluate_shadow

rng = np.random.default_rng(0)


def frame(shift=0.0, n=1000, cols=("a", "b", "c")):
    return pd.DataFrame({c: rng.normal(shift, 1, n) for c in cols})


def test_psi_identical_vs_shifted():
    a = rng.normal(0, 1, 5000)
    assert psi(a, rng.normal(0, 1, 5000)) < 0.05
    assert psi(a, rng.normal(2, 1, 5000)) > 0.25
    assert psi(np.ones(100), np.ones(100)) == 0.0          # constant reference is handled


def test_drift_report_detects_shift_only_when_present():
    ref = frame()
    assert not drift_report(ref, frame(), ["a", "b", "c"])["drift_detected"]
    shifted = drift_report(ref, frame(shift=1.5), ["a", "b", "c"])
    assert shifted["drift_detected"] and shifted["n_drifted"] == 3
    few = drift_report(ref, frame(n=50), ["a", "b", "c"])
    assert not few["drift_detected"] and "insufficient" in few["reason"]


def test_drift_requires_enough_features_to_drift():
    ref = frame(cols=tuple("abcdefghij"))
    rec = ref.sample(1000, replace=True, random_state=1).reset_index(drop=True)
    rec["a"] += 3                                              # 1 of 10 features: below min_share 0.2
    r = drift_report(ref, rec, list(ref.columns))
    assert r["n_drifted"] == 1 and not r["drift_detected"]


def _preds():
    t0 = pd.Timestamp("2026-06-01T10:00:00Z")
    rows = []
    for i in range(120):
        rows.append({"machineId": "M-001", "ts": t0 + pd.Timedelta(minutes=i),
                     "recommendation": "INSPECT_MACHINE" if 60 <= i < 90 else "CONTINUE_MONITORING"})
    return pd.DataFrame(rows), t0


def test_feedback_performance_and_labels():
    p, t0 = _preds()
    ev = [{"machineId": "M-001", "outcome": "FAULT_CONFIRMED", "performedAt": t0 + pd.Timedelta(minutes=85)},   # alarmed -> true alarm
          {"machineId": "M-001", "outcome": "FAULT_CONFIRMED", "performedAt": t0 + pd.Timedelta(minutes=20)},   # quiet -> miss
          {"machineId": "M-001", "outcome": "NO_FAULT_FOUND", "performedAt": t0 + pd.Timedelta(minutes=80)},    # alarmed -> false alarm
          {"machineId": "M-001", "outcome": "NO_FAULT_FOUND", "performedAt": t0 + pd.Timedelta(minutes=125)},   # quiet -> correct
          {"machineId": "M-001", "outcome": "PREVENTIVE", "performedAt": t0 + pd.Timedelta(minutes=50)}]       # ignored
    perf = feedback_performance(p, ev)
    assert (perf["true_alarms"], perf["missed_faults"], perf["false_alarms"], perf["correct_quiet"]) == (1, 1, 1, 1)
    assert perf["miss_rate"] == 0.5 and perf["false_alarm_rate"] == 0.5
    assert not feedback_degraded(perf, min_events=5)           # too few events to judge
    assert feedback_degraded(perf, min_events=4)
    lab = label_predictions(p, ev[:1])
    assert lab["y"].sum() == 11 and lab["y"].notna().sum() == 11   # 10-minute lookback window (inclusive)


def test_shadow_gating():
    n = 400
    y = np.r_[np.zeros(n // 2), np.ones(n // 2)]
    good = np.clip(y * 0.8 + rng.normal(0.1, 0.05, n), 0, 1)
    bad = np.clip(rng.uniform(0, 1, n), 0, 1)
    ok = evaluate_shadow(pd.DataFrame({"p_active": bad, "p_shadow": good, "y": y}), 0.5, 0.5)
    assert ok["promotion_ready"] and ok["shadow"]["f1"] > ok["active"]["f1"]
    worse = evaluate_shadow(pd.DataFrame({"p_active": good, "p_shadow": bad, "y": y}), 0.5, 0.5)
    assert not worse["promotion_ready"]
    few = evaluate_shadow(pd.DataFrame({"p_active": good[:50], "p_shadow": good[:50], "y": y[:50]}), 0.5, 0.5)
    assert not few["promotion_ready"] and "labelled" in few["reason"]
    unl = evaluate_shadow(pd.DataFrame({"p_active": good, "p_shadow": good, "y": np.nan}), 0.5, 0.5)
    assert not unl["promotion_ready"] and unl["n_labelled"] == 0


def test_candidate_is_registered_but_never_activated(trained_registry, small_dataset, tmp_path):
    d = tmp_path / "reg"; shutil.copytree(trained_registry, d)
    reg = Registry(d)
    before = reg.active
    recent = small_dataset.sample(600, random_state=0)[["t", "y"] + FEATURE_NAMES]
    out = train_candidate(reg, recent, historical=small_dataset, seed=3, trigger={"drift": True})
    st = reg.state()
    assert out["version"] == "v0002" and st["active"] == before == "v0001"
    assert st["versions"]["v0002"]["status"] == "CANDIDATE" and st["versions"]["v0002"]["parent"] == "v0001"
    metrics = reg.read_json("v0002", "metrics.json")["recent_holdout"]
    assert metrics["active_version"] == "v0001" and "random_forest" in metrics["candidate"]
    assert "reference" in reg.load_bundle("v0002")
    reg.set_shadow("v0002"); reg.set_active("v0002")             # what the ADMIN promotion does
    assert reg.state()["previous"] == "v0001"
    assert reg.rollback() == "v0001"


def test_mongo_source_normalises_and_reads_feedback():
    mongomock = pytest.importorskip("mongomock")
    from learning.sources import MongoSource
    src = MongoSource.__new__(MongoSource)
    src.db = mongomock.MongoClient()["intellitwin"]
    base = {"temperature": 55.0, "vibration": 2.8, "pressure": 6.5, "current": 22.0, "speed": 2950.0}
    src.db["machines"].insert_one({"machineId": "M-002", "baselines": {k: {"mean": v, "std": 0.1} for k, v in base.items()}})
    rows = [{"temperature": 55, "vibration": 2.8, "pressure": 6.5, "current": 22, "speed": 2950}] * 30
    now = datetime.now(timezone.utc)
    src.db["predictions"].insert_one({"machineId": "M-002", "ts": now, "createdAt": now, "features": compute_features(rows),
                                      "failureProbability": 0.1, "recommendation": "CONTINUE_MONITORING", "risk": 0.05,
                                      "modelVersion": "v0001", "shadow": {"modelVersion": "v0002", "failureProbability": 0.2}})
    src.db["maintenance_records"].insert_one({"machineId": "M-002", "outcome": "NO_FAULT_FOUND", "performedAt": now})
    df = src.predictions(now - timedelta(hours=1))
    assert len(df) == 1 and df.loc[0, "vibration_rms"] == pytest.approx(1.0) and df.loc[0, "p_shadow"] == 0.2
    assert src.maintenance(now - timedelta(hours=1))[0]["outcome"] == "NO_FAULT_FOUND"
