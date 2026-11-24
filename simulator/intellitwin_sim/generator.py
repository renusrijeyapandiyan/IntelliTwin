"""Seeded sensor generator: healthy operation + injectable faults.

Healthy model: load random-walk with step changes, first-order thermal lag,
sensor noise, start-up transient (speed ramp + current inrush).
Faults (severity s in [0,1]):
  bearing_wear : vibration up, impulsive spikes (crest factor), temperature up
  jam          : current spike, speed drop, temperature up
  leakage      : pressure decay
  imbalance    : vibration up (periodic), speed ripple
  lube_loss    : temperature up strongly, vibration & current slightly up
"""
from __future__ import annotations
import math
from datetime import datetime, timedelta, timezone
from typing import Dict, List, Optional

import numpy as np

from .faults import DEFAULT_RAMP_SECONDS, DEGRADED_SEVERITY, FAULT_TYPES, Fault
from .machines import MachineProfile

STARTUP_SECONDS = 25.0
NOMINAL_LOAD = 0.8


def _load_factor(load: float, a: float, b: float) -> float:
    """Linear in load, normalised to 1.0 at nominal load."""
    return (a + b * load) / (a + b * NOMINAL_LOAD)


class MachineSimulator:
    def __init__(self, profile: MachineProfile, seed: Optional[int] = 42, dt: float = 1.0,
                 start_time: Optional[datetime] = None, start_running: bool = True,
                 glitches: bool = False):
        self.p = profile
        self.dt = dt
        self.rng = np.random.default_rng(seed)
        self.t = 0.0                    # simulated seconds
        self.seq = 0
        self.start_time = start_time
        self.glitches = glitches
        self.faults: Dict[str, Fault] = {}
        self.load = 0.8
        self._load_target = 0.8
        self._next_load_change = float(self.rng.uniform(60, 180))
        self.temp_state = profile.ambient + 8.0
        self.mode = "STARTUP" if start_running else "STOPPED"
        self.mode_since = 0.0

    # ---------------- control ----------------
    def inject_fault(self, name: str, ramp_seconds: Optional[float] = None, severity: float = 1.0):
        if name not in FAULT_TYPES:
            raise ValueError(f"unknown fault '{name}', expected one of {FAULT_TYPES}")
        ramp = DEFAULT_RAMP_SECONDS[name] if ramp_seconds is None else float(ramp_seconds)
        self.faults[name] = Fault(name, self.t, ramp, float(min(max(severity, 0.0), 1.0)))

    def clear_fault(self, name: Optional[str] = None):
        if name is None:
            self.faults.clear()
        else:
            self.faults.pop(name, None)

    def stop(self):
        self.mode, self.mode_since = "STOPPED", self.t

    def start(self):
        if self.mode == "STOPPED":
            self.mode, self.mode_since = "STARTUP", self.t

    def severities(self) -> Dict[str, float]:
        return {n: f.severity(self.t) for n, f in self.faults.items()}

    # ---------------- stepping ----------------
    def _update_load(self):
        if self.t >= self._next_load_change:
            self._load_target = float(self.rng.uniform(0.6, 1.0))
            self._next_load_change = self.t + float(self.rng.uniform(60, 180))
        self.load += 0.08 * (self._load_target - self.load)

    def step(self, ts: Optional[datetime] = None) -> dict:
        """Advance one tick and return a reading (with private `_label_*` fields)."""
        p, rng = self.p, self.rng
        self.t += self.dt
        self.seq += 1
        if ts is None:
            base = self.start_time or datetime(2026, 1, 1, tzinfo=timezone.utc)
            ts = base + timedelta(seconds=self.t)
        self._update_load()
        L = self.load
        sev = self.severities()
        s = lambda n: sev.get(n, 0.0)  # noqa: E731

        if self.mode == "STARTUP" and self.t - self.mode_since >= STARTUP_SECONDS:
            self.mode = "RUNNING"
        since = self.t - self.mode_since

        n = lambda sensor: float(rng.normal(0.0, p.noise[sensor]))  # noqa: E731 relative noise

        if self.mode == "STOPPED":
            speed = p.speed * max(0.0, math.exp(-since / 4.0)) * (1 + n("speed"))
            current = p.current * 0.03 * (1 + abs(n("current")) * 5)
            vib = p.vibration * 0.05 * (1 + abs(n("vibration")))
            self.temp_state += 0.01 * (p.ambient - self.temp_state)
            temp = self.temp_state + 0.3 * float(rng.normal())
            pressure = p.pressure * max(0.0, 0.1 + 0.9 * math.exp(-since / 20.0)) * (1 + n("pressure"))
            state = "STOPPED"
        else:
            startup = self.mode == "STARTUP"
            ramp = 1 - math.exp(-since / 6.0) if startup else 1.0
            speed_f = 1 - 0.02 * (L / NOMINAL_LOAD - 1) * 0.5
            speed = p.speed * speed_f * ramp * (1 + n("speed"))
            speed *= 1 - 0.60 * s("jam")
            if s("imbalance") > 0:
                speed *= 1 + 0.004 * s("imbalance") * math.sin(self.t * 1.7)

            current = p.current * _load_factor(L, 0.35, 0.65) * (1 + n("current"))
            if startup:
                current *= 1 + 3.5 * math.exp(-since / 5.0)
            current *= 1 + 1.2 * s("jam") + 0.15 * s("lube_loss") + 0.05 * s("bearing_wear")

            ss = p.ambient + (p.temperature - p.ambient) * _load_factor(L, 0.4, 0.6)
            ss += 18.0 * s("bearing_wear") + 8.0 * s("jam") + 26.0 * s("lube_loss") + 2.0 * s("imbalance")
            self.temp_state += 0.02 * (ss - self.temp_state) * self.dt
            temp = self.temp_state + p.temperature * p.noise["temperature"] * float(rng.normal()) * 2.0

            vib_scale = 1 + 2.2 * s("bearing_wear") + 1.2 * s("imbalance") + 0.6 * s("lube_loss") + 0.5 * s("jam")
            vib = p.vibration * (0.9 + 0.1 * L / NOMINAL_LOAD) * vib_scale * (1 + n("vibration"))
            if s("imbalance") > 0:
                vib *= 1 + 0.12 * s("imbalance") * math.sin(self.t * 2 * math.pi * 0.31)
            if s("bearing_wear") > 0 and rng.random() < 0.04 + 0.28 * s("bearing_wear"):
                vib *= 1 + (1.0 + 3.0 * s("bearing_wear")) * float(rng.uniform(0.4, 1.0))  # impulse
            vib = max(vib, 0.0)

            pressure = p.pressure * (0.98 + 0.025 * L / NOMINAL_LOAD) * (1 + n("pressure"))
            pressure *= 1 - 0.45 * s("leakage")
            if startup:
                pressure *= 0.5 + 0.5 * ramp
            state = "STARTUP" if startup else "RUNNING"

        reading = {
            "machineId": p.machine_id,
            "ts": ts.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "seq": self.seq,
            "temperature": round(float(temp), 3),
            "vibration": round(float(vib), 4),
            "pressure": round(float(pressure), 4),
            "current": round(float(current), 3),
            "speed": round(float(speed), 2),
            "state": state,
            # ---- private ground truth, never sent to the backend ----
            "_fault": max(sev, key=sev.get) if sev and max(sev.values()) > 0 else "none",
            "_severity": round(max(sev.values()), 4) if sev else 0.0,
        }
        reading["_degraded"] = int(reading["_severity"] >= DEGRADED_SEVERITY and state == "RUNNING")
        if self.glitches:
            reading = self._apply_glitches(reading)
        return reading

    def _apply_glitches(self, r: dict) -> dict:
        """Optional data-quality problems to exercise validation (off by default)."""
        x = self.rng.random()
        if x < 0.005:
            r[str(self.rng.choice(["temperature", "vibration", "pressure", "current", "speed"]))] = None
        elif x < 0.008:
            r["temperature"] = 9999.0  # physically impossible
        return r


def public_payload(reading: dict) -> dict:
    """Strip private ground-truth fields before sending to the backend."""
    return {k: v for k, v in reading.items() if not k.startswith("_")}


def make_fleet(machine_ids: List[str], seed: Optional[int], profiles: dict, **kw) -> Dict[str, MachineSimulator]:
    fleet = {}
    for i, mid in enumerate(machine_ids):
        fleet[mid] = MachineSimulator(profiles[mid], seed=None if seed is None else seed + i, **kw)
    return fleet
