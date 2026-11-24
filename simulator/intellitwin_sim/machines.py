"""Machine profiles. Baselines are the nominal operating point at 80% load."""
from dataclasses import dataclass, field
from typing import Dict

SENSORS = ["temperature", "vibration", "pressure", "current", "speed"]


@dataclass(frozen=True)
class MachineProfile:
    machine_id: str
    name: str
    type: str
    location: str
    temperature: float   # degC at nominal load
    vibration: float     # mm/s (velocity magnitude)
    pressure: float      # bar
    current: float       # A
    speed: float         # rpm
    ambient: float = 25.0
    # relative noise (fraction of baseline, 1 sigma)
    noise: Dict[str, float] = field(default_factory=lambda: {
        "temperature": 0.004, "vibration": 0.07, "pressure": 0.008,
        "current": 0.012, "speed": 0.002})

    def baseline_means(self) -> Dict[str, float]:
        return {s: getattr(self, s) for s in SENSORS}

    def baselines(self) -> Dict[str, Dict[str, float]]:
        """{sensor: {mean, std}} for the machine document."""
        return {s: {"mean": getattr(self, s),
                    "std": round(getattr(self, s) * self.noise[s], 4)} for s in SENSORS}


PROFILES = {
    "M-001": MachineProfile("M-001", "Drive Motor", "MOTOR", "Line 1",
                            temperature=62.0, vibration=2.0, pressure=3.0, current=35.0, speed=1480.0),
    "M-002": MachineProfile("M-002", "Coolant Pump", "PUMP", "Line 1",
                            temperature=55.0, vibration=2.8, pressure=6.5, current=22.0, speed=2950.0),
    "M-003": MachineProfile("M-003", "Air Compressor", "COMPRESSOR", "Utilities",
                            temperature=78.0, vibration=3.2, pressure=8.0, current=48.0, speed=1780.0),
}
