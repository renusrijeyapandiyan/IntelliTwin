"""Performance tracking against maintenance feedback, and weak labels derived from it.

Maintenance outcomes:  FAULT_CONFIRMED  -> machine really was degraded
                       NO_FAULT_FOUND   -> an alarm (if any) was a false alarm
Prediction windows shortly before a record inherit its outcome as a (noisy) label.
"""
from __future__ import annotations
from datetime import timedelta
from typing import Dict, List

import pandas as pd

ALARM_RECOMMENDATIONS = {"INSPECT_MACHINE", "SCHEDULE_MAINTENANCE", "IMMEDIATE_INTERVENTION"}
CONFIRMED_LOOKBACK = timedelta(minutes=10)
NO_FAULT_LOOKBACK = timedelta(minutes=30)


def feedback_performance(preds: pd.DataFrame, events: List[Dict]) -> Dict:
    """Did the system alarm before maintenance found (or did not find) a fault?"""
    tp = fn = fp = tn = 0
    for ev in events:
        if ev["outcome"] not in ("FAULT_CONFIRMED", "NO_FAULT_FOUND"):
            continue
        t = pd.Timestamp(ev["performedAt"])
        w = preds[(preds["machineId"] == ev["machineId"]) & (preds["ts"] <= t) & (preds["ts"] >= t - NO_FAULT_LOOKBACK)]
        alarmed = bool(len(w) and w["recommendation"].isin(ALARM_RECOMMENDATIONS).any())
        if ev["outcome"] == "FAULT_CONFIRMED":
            tp, fn = tp + alarmed, fn + (not alarmed)
        else:
            fp, tn = fp + alarmed, tn + (not alarmed)
    n = tp + fn + fp + tn
    return {"events": n, "true_alarms": tp, "missed_faults": fn, "false_alarms": fp, "correct_quiet": tn,
            "miss_rate": fn / (tp + fn) if tp + fn else None, "false_alarm_rate": fp / (fp + tn) if fp + tn else None}


def feedback_degraded(perf: Dict, min_events: int = 5, max_miss: float = 0.3, max_false_alarm: float = 0.5) -> bool:
    if perf["events"] < min_events:
        return False
    return (perf["miss_rate"] or 0) > max_miss or (perf["false_alarm_rate"] or 0) > max_false_alarm


def label_predictions(preds: pd.DataFrame, events: List[Dict]) -> pd.DataFrame:
    """Return preds with an added `y` column (1/0) where feedback allows, NaN otherwise."""
    out = preds.copy()
    out["y"] = float("nan")
    for ev in events:
        t = pd.Timestamp(ev["performedAt"])
        m = out["machineId"] == ev["machineId"]
        if ev["outcome"] == "FAULT_CONFIRMED":
            out.loc[m & (out["ts"] <= t) & (out["ts"] >= t - CONFIRMED_LOOKBACK), "y"] = 1.0
        elif ev["outcome"] == "NO_FAULT_FOUND":
            out.loc[m & (out["ts"] <= t) & (out["ts"] >= t - NO_FAULT_LOOKBACK), "y"] = 0.0
    return out
