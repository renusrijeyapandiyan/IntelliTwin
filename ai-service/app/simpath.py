"""Make the simulator package importable both locally (../simulator) and in Docker (/app/simulator)."""
import sys
from pathlib import Path

for cand in (Path(__file__).resolve().parents[2] / "simulator", Path("/app/simulator")):
    if (cand / "intellitwin_sim").exists() and str(cand) not in sys.path:
        sys.path.insert(0, str(cand))
