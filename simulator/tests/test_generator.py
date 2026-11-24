import numpy as np
import pytest

from intellitwin_sim.faults import DEGRADED_SEVERITY, FAULT_TYPES
from intellitwin_sim.generator import MachineSimulator, public_payload
from intellitwin_sim.machines import PROFILES


def run(sim, n):
    return [sim.step() for _ in range(n)]


def mean(rows, key, state="RUNNING"):
    return float(np.mean([r[key] for r in rows if r["state"] == state]))


def test_seed_is_deterministic():
    a = run(MachineSimulator(PROFILES["M-001"], seed=7), 200)
    b = run(MachineSimulator(PROFILES["M-001"], seed=7), 200)
    assert a == b
    c = run(MachineSimulator(PROFILES["M-001"], seed=8), 200)
    assert a != c


def test_startup_transient_then_running():
    rows = run(MachineSimulator(PROFILES["M-001"], seed=1), 60)
    assert rows[0]["state"] == "STARTUP" and rows[-1]["state"] == "RUNNING"
    assert rows[1]["speed"] < PROFILES["M-001"].speed * 0.5          # speed ramps
    assert rows[1]["current"] > PROFILES["M-001"].current * 1.8      # inrush


@pytest.mark.parametrize("mid", list(PROFILES))
def test_healthy_close_to_baseline(mid):
    p = PROFILES[mid]
    rows = run(MachineSimulator(p, seed=3), 600)[60:]
    for s in ["temperature", "vibration", "pressure", "current", "speed"]:
        assert abs(mean(rows, s) / getattr(p, s) - 1) < 0.15, s


def healthy_vs_faulty(fault, seed=5):
    h = MachineSimulator(PROFILES["M-002"], seed=seed)
    f = MachineSimulator(PROFILES["M-002"], seed=seed)
    run(h, 200); run(f, 200)
    f.inject_fault(fault, ramp_seconds=60)
    return run(h, 150)[-60:], run(f, 150)[-60:]


def test_bearing_wear_raises_vibration_temp_and_crest():
    h, f = healthy_vs_faulty("bearing_wear")
    assert mean(f, "vibration") > 2.0 * mean(h, "vibration")
    assert mean(f, "temperature") > mean(h, "temperature") + 8
    crest = lambda r: max(x["vibration"] for x in r) / np.sqrt(np.mean([x["vibration"] ** 2 for x in r]))  # noqa
    assert crest(f) > crest(h)


def test_jam_current_up_speed_down():
    h, f = healthy_vs_faulty("jam")
    assert mean(f, "current") > 1.8 * mean(h, "current")
    assert mean(f, "speed") < 0.6 * mean(h, "speed")


def test_leakage_pressure_decays():
    h, f = healthy_vs_faulty("leakage")
    assert mean(f, "pressure") < 0.65 * mean(h, "pressure")


def test_imbalance_and_lube_loss():
    h, f = healthy_vs_faulty("imbalance")
    assert mean(f, "vibration") > 1.8 * mean(h, "vibration")
    h, f = healthy_vs_faulty("lube_loss")
    assert mean(f, "temperature") > mean(h, "temperature") + 15


def test_bearing_wear_is_gradual():
    s = MachineSimulator(PROFILES["M-001"], seed=2)
    run(s, 100)
    s.inject_fault("bearing_wear", ramp_seconds=300)
    early = mean(run(s, 60), "vibration")
    run(s, 180)
    late = mean(run(s, 60), "vibration")
    assert late > early * 1.5


def test_labels_private_and_stripped():
    s = MachineSimulator(PROFILES["M-001"], seed=2)
    run(s, 50)
    s.inject_fault("jam", ramp_seconds=10)
    rows = run(s, 30)
    assert rows[-1]["_fault"] == "jam" and rows[-1]["_degraded"] == 1
    assert not any(k.startswith("_") for k in public_payload(rows[-1]))
    assert rows[0]["_severity"] < DEGRADED_SEVERITY


def test_unknown_fault_rejected_and_clear():
    s = MachineSimulator(PROFILES["M-001"], seed=2)
    with pytest.raises(ValueError):
        s.inject_fault("meteor")
    s.inject_fault("leakage"); s.clear_fault()
    assert s.severities() == {}


def test_stop_start_cycle():
    s = MachineSimulator(PROFILES["M-001"], seed=2)
    run(s, 40); s.stop()
    r = run(s, 30)[-1]
    assert r["state"] == "STOPPED" and r["speed"] < 5
    s.start()
    assert s.step()["state"] == "STARTUP"


def test_glitch_mode_produces_bad_values():
    s = MachineSimulator(PROFILES["M-001"], seed=4, glitches=True)
    rows = run(s, 4000)
    assert any(None in (r["temperature"], r["vibration"], r["pressure"], r["current"], r["speed"]) for r in rows)
    assert any(r["temperature"] == 9999.0 for r in rows)
