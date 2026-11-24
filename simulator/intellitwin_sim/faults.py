"""Fault definitions. Severity ramps 0 -> max over `ramp_seconds` of simulated time."""
from dataclasses import dataclass

FAULT_TYPES = ["bearing_wear", "jam", "leakage", "imbalance", "lube_loss"]

DEFAULT_RAMP_SECONDS = {
    "bearing_wear": 600.0,  # gradual
    "lube_loss": 300.0,
    "leakage": 240.0,
    "imbalance": 120.0,
    "jam": 20.0,            # sudden
}

# severity at/above which the machine is labelled "degraded" (training target)
DEGRADED_SEVERITY = 0.30


@dataclass
class Fault:
    name: str
    started_at: float        # simulated seconds
    ramp_seconds: float
    max_severity: float = 1.0

    def severity(self, t: float) -> float:
        if t < self.started_at:
            return 0.0
        frac = (t - self.started_at) / max(self.ramp_seconds, 1e-6)
        return self.max_severity * min(1.0, frac)
