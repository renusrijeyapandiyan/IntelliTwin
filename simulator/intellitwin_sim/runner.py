"""Realtime loop: one reading per machine per interval."""
from __future__ import annotations
import logging
import threading
import time
from datetime import datetime, timezone
from typing import Callable, Dict, List, Optional

from .generator import MachineSimulator, make_fleet, public_payload
from .machines import PROFILES

log = logging.getLogger("intellitwin.sim")


class Runner:
    def __init__(self, machine_ids: List[str], seed: Optional[int], interval: float,
                 sink: Callable[[dict], bool], glitches: bool = False):
        self.fleet: Dict[str, MachineSimulator] = make_fleet(machine_ids, seed, PROFILES, glitches=glitches)
        self.interval = interval
        self.sink = sink
        self.lock = threading.Lock()
        self._stop = threading.Event()
        self.thread: Optional[threading.Thread] = None
        self.ticks = 0

    def start(self):
        self.thread = threading.Thread(target=self._loop, daemon=True, name="sim-loop")
        self.thread.start()

    def stop(self):
        self._stop.set()

    def _loop(self):
        next_t = time.monotonic()
        while not self._stop.is_set():
            now = datetime.now(timezone.utc)
            with self.lock:
                readings = [m.step(ts=now) for m in self.fleet.values()]
            for r in readings:
                self.sink(public_payload(r))
            self.ticks += 1
            next_t += self.interval
            delay = next_t - time.monotonic()
            if delay > 0:
                self._stop.wait(delay)
            else:
                next_t = time.monotonic()   # fell behind; resync

    def status(self) -> dict:
        with self.lock:
            return {mid: {"mode": m.mode, "load": round(m.load, 3), "seq": m.seq,
                          "faults": {k: round(v, 3) for k, v in m.severities().items()}}
                    for mid, m in self.fleet.items()}
