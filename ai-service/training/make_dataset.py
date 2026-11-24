"""Generate a labelled, chronologically ordered dataset from the simulator.

Per machine the timeline is a sequence of cycles: [healthy period] -> [fault ramps up, holds] -> [cleared].
Label y = 1 when the injected fault severity >= DEGRADED_SEVERITY (machine is degraded and needs attention).
Rows are produced every `stride` ticks from a rolling window of the last 30 RUNNING readings,
exactly as the backend does at serving time.
"""
from __future__ import annotations
import argparse
from collections import deque
from pathlib import Path
from typing import Dict, Optional

import numpy as np
import pandas as pd

from app import simpath  # noqa: F401
from app.features import FEATURE_NAMES, MIN_SAMPLES, WARMUP_SAMPLES, WINDOW, compute_features, normalize
from intellitwin_sim.faults import DEFAULT_RAMP_SECONDS, FAULT_TYPES
from intellitwin_sim.generator import MachineSimulator
from intellitwin_sim.machines import PROFILES, MachineProfile

SENSOR_KEYS = ["temperature", "vibration", "pressure", "current", "speed"]


def simulate_machine(profile: MachineProfile, seed: int, cycles: int, stride: int = 5,
                     distort: Optional[Dict[str, tuple]] = None) -> pd.DataFrame:
    """distort: {sensor: (gain, offset)} applied to readings (used to simulate sensor/process drift)."""
    rng = np.random.default_rng(seed + 10_000)
    sim = MachineSimulator(profile, seed=seed)
    win: deque = deque(maxlen=WINDOW)
    rows = []
    run_count = [0]
    order = []
    while len(order) < cycles:
        perm = list(FAULT_TYPES)
        rng.shuffle(perm)
        order += perm
    order = order[:cycles]

    def tick():
        r = sim.step()
        if distort:
            for k, (g, o) in distort.items():
                r[k] = r[k] * g + o
        if r["state"] == "RUNNING":
            win.append({k: r[k] for k in SENSOR_KEYS})
            run_count[0] += 1
        else:
            win.clear()
            run_count[0] = 0
        # serving rule: no prediction until the machine has been RUNNING for WARMUP_SAMPLES readings
        if sim.seq % stride == 0 and len(win) >= MIN_SAMPLES and run_count[0] >= WARMUP_SAMPLES:
            f = normalize(compute_features(win), profile.baseline_means())
            rows.append({"machineId": profile.machine_id, "t": sim.t, "fault": r["_fault"],
                         "severity": r["_severity"], "y": r["_degraded"], **f})

    for _ in range(WARMUP_SAMPLES + 30):
        tick()                                  # start-up transient + thermal warm-up (no rows emitted)
    for fault in order:
        for _ in range(int(rng.integers(200, 400))):
            tick()
        ramp = DEFAULT_RAMP_SECONDS[fault] * float(rng.uniform(0.5, 1.5))
        sim.inject_fault(fault, ramp_seconds=ramp, severity=float(rng.uniform(0.5, 1.0)))
        for _ in range(int(ramp + rng.integers(60, 120))):
            tick()
        sim.clear_fault()
        if rng.random() < 0.25:                 # occasional stop/start to produce transients
            sim.stop()
            for _ in range(20):
                tick()
            sim.start()
            for _ in range(WARMUP_SAMPLES + 40):
                tick()
    return pd.DataFrame(rows)


def make_dataset(seed: int = 42, cycles: int = 24, stride: int = 5, machines=None, distort=None) -> pd.DataFrame:
    frames = []
    for i, mid in enumerate(machines or list(PROFILES)):
        frames.append(simulate_machine(PROFILES[mid], seed + 100 * i, cycles, stride, distort))
    return pd.concat(frames, ignore_index=True)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--cycles", type=int, default=24)
    ap.add_argument("--out", default="training/data/dataset.csv")
    a = ap.parse_args()
    df = make_dataset(a.seed, a.cycles)
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(a.out, index=False)
    print(f"wrote {len(df)} rows -> {a.out}; positives={int(df.y.sum())} ({df.y.mean():.1%})")
