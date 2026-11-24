"""Loads the ACTIVE (and optional SHADOW) model bundle; hot-reloads when registry.json changes."""
from __future__ import annotations
import threading
import time
from typing import Any, Dict, List, Optional

import numpy as np

from .features import FEATURE_NAMES, to_vector
from .registry import Registry


class LoadedModel:
    def __init__(self, version: str, bundle: Dict[str, Any], meta: Dict[str, Any]):
        self.version, self.bundle, self.meta = version, bundle, meta
        self.mean = np.array([meta["healthy_mean"][k] for k in FEATURE_NAMES])
        self.std = np.array([meta["healthy_std"][k] for k in FEATURE_NAMES])
        self.imp = np.array([meta["importances"][k] for k in FEATURE_NAMES])

    def score(self, vec: np.ndarray) -> Dict[str, Any]:
        x = vec.reshape(1, -1)
        p = float(self.bundle["rf"].predict_proba(x)[0, 1])
        raw = float(-self.bundle["iforest"].score_samples(x)[0])
        lo, hi = self.bundle["anomaly_lo"], self.bundle["anomaly_hi"]
        norm = float(np.clip((raw - lo) / (hi - lo), 0.0, 1.0))
        return {"p": p, "anomaly_raw": raw, "anomaly": norm}

    def contributions(self, vec: np.ndarray, top: int = 5) -> List[Dict[str, Any]]:
        z = (vec - self.mean) / self.std
        contrib = self.imp * np.minimum(np.abs(z), 10.0)
        idx = np.argsort(contrib)[::-1][:top]
        return [{"name": FEATURE_NAMES[i], "value": float(vec[i]), "zScore": float(z[i]),
                 "importance": float(self.imp[i]), "contribution": float(contrib[i]),
                 "direction": "HIGH" if z[i] >= 0 else "LOW"} for i in idx]


class ModelStore:
    def __init__(self, registry: Registry):
        self.registry = registry
        self._lock = threading.RLock()
        self._mtime = 0.0
        self.active: Optional[LoadedModel] = None
        self.shadow: Optional[LoadedModel] = None
        self.refresh(force=True)

    def _load(self, version: Optional[str]) -> Optional[LoadedModel]:
        if not version:
            return None
        return LoadedModel(version, self.registry.load_bundle(version), self.registry.read_json(version, "meta.json"))

    def refresh(self, force: bool = False):
        with self._lock:
            m = self.registry.mtime()
            if not force and m == self._mtime:
                return
            st = self.registry.state()
            if not self.active or self.active.version != st["active"]:
                self.active = self._load(st["active"])
            if (self.shadow.version if self.shadow else None) != st["shadow"]:
                self.shadow = self._load(st["shadow"])
            self._mtime = m

    def vector(self, norm_features: Dict[str, float]) -> np.ndarray:
        return to_vector(norm_features)
