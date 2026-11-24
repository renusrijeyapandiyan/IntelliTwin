import json
import math
from pathlib import Path

import pytest

from app.features import FEATURE_NAMES, MIN_SAMPLES, compute_features, normalize

GOLDEN = json.loads((Path(__file__).resolve().parents[2] / "shared" / "feature_golden.json").read_text())


def test_matches_shared_golden_fixture():
    f = compute_features(GOLDEN["readings"])
    assert set(f) == set(FEATURE_NAMES)
    for k, v in GOLDEN["features"].items():
        assert f[k] == pytest.approx(v, rel=1e-9, abs=1e-9), k
    n = normalize(f, GOLDEN["baselines"])
    for k, v in GOLDEN["normalized"].items():
        assert n[k] == pytest.approx(v, rel=1e-9, abs=1e-9), k


def test_known_values():
    rows = [{"temperature": 10 + i, "vibration": 3.0, "pressure": 5, "current": 2, "speed": 100} for i in range(10)]
    f = compute_features(rows)
    assert f["temperature_mean"] == pytest.approx(14.5)
    assert f["temperature_slope"] == pytest.approx(1.0)
    assert f["temperature_min"] == 10 and f["temperature_max"] == 19
    assert f["temperature_std"] == pytest.approx(math.sqrt(8.25))
    assert f["vibration_rms"] == pytest.approx(3.0) and f["vibration_crest"] == pytest.approx(1.0)


def test_crest_factor_rises_with_impulse():
    base = [{"temperature": 1, "vibration": 2.0, "pressure": 1, "current": 1, "speed": 1}] * 20
    spiky = base[:-1] + [{"temperature": 1, "vibration": 8.0, "pressure": 1, "current": 1, "speed": 1}]
    assert compute_features(spiky)["vibration_crest"] > compute_features(base)["vibration_crest"] * 2


def test_too_few_samples_rejected_and_window_truncation():
    row = {"temperature": 1, "vibration": 1, "pressure": 1, "current": 1, "speed": 1}
    with pytest.raises(ValueError):
        compute_features([row] * (MIN_SAMPLES - 1))
    old = [{**row, "temperature": 1000}] * 50
    new = [{**row, "temperature": 5}] * 30
    assert compute_features(old + new)["temperature_mean"] == 5   # only the last 30 used


def test_normalize_rejects_bad_baseline():
    f = compute_features(GOLDEN["readings"])
    with pytest.raises(ValueError):
        normalize(f, {**GOLDEN["baselines"], "pressure": 0})
