"""Data sources for the learning loop: MongoDB (production) and a simulated drifted stream (demo/tests)."""
from __future__ import annotations
import os
from datetime import datetime, timedelta, timezone
from typing import Dict, List, Optional

import numpy as np
import pandas as pd

from app import simpath  # noqa: F401
from app.features import FEATURE_NAMES, normalize


def _utc(ts) -> pd.Timestamp:
    """MongoDB hands back naive UTC datetimes; normalise everything to tz-aware UTC."""
    t = pd.Timestamp(ts)
    return t.tz_localize("UTC") if t.tzinfo is None else t.tz_convert("UTC")


class MongoSource:
    """Reads predictions (raw window features + model outputs) and maintenance feedback written by the backend."""

    def __init__(self, uri: Optional[str] = None, db: Optional[str] = None):
        from pymongo import MongoClient
        uri = uri or os.environ["MONGO_URI"]
        self.client = MongoClient(uri, serverSelectionTimeoutMS=4000)
        self.db = self.client.get_default_database() if db is None else self.client[db]

    def baselines(self) -> Dict[str, Dict[str, float]]:
        return {m["machineId"]: {s: v["mean"] for s, v in m["baselines"].items()} for m in self.db["machines"].find()}

    def predictions(self, since: datetime, limit: int = 50000) -> pd.DataFrame:
        base = self.baselines()
        rows = []
        for d in self.db["predictions"].find({"createdAt": {"$gte": since}, "features": {"$exists": True}}).sort("ts", 1).limit(limit):
            b = base.get(d["machineId"])
            if not b or not d.get("features"):
                continue
            n = normalize(d["features"], b)
            sh = d.get("shadow") or {}
            rows.append({"machineId": d["machineId"], "ts": _utc(d["ts"]),
                         "p_active": d["failureProbability"], "recommendation": d["recommendation"], "risk": d.get("risk"),
                         "modelVersion": d.get("modelVersion"), "p_shadow": sh.get("failureProbability"),
                         "shadowVersion": sh.get("modelVersion"), **n})
        return pd.DataFrame(rows)

    def maintenance(self, since: datetime) -> List[Dict]:
        return [{"machineId": r["machineId"], "outcome": r["outcome"], "performedAt": _utc(r["performedAt"])}
                for r in self.db["maintenance_records"].find({"performedAt": {"$gte": since}})]


class SimulatedSource:
    """Simulated drift: sensors are re-calibrated (gain/offset), ground truth stands in for maintenance feedback."""

    def __init__(self, distort: Dict[str, tuple], seed: int = 123, cycles: int = 10):
        from training.make_dataset import make_dataset
        self.df = make_dataset(seed=seed, cycles=cycles, distort=distort)
        self.df["ts"] = pd.Timestamp("2026-06-01", tz="UTC") + pd.to_timedelta(self.df["t"], unit="s")

    def frame(self) -> pd.DataFrame:
        return self.df
