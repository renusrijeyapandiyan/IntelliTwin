import pytest
from fastapi.testclient import TestClient

from app.features import compute_features
from app.main import create_app
from app.registry import Registry
from intellitwin_sim.generator import MachineSimulator
from intellitwin_sim.machines import PROFILES

KEY = {"X-API-Key": "test-key"}


def payload(fault=None, mid="M-002", seconds=240):
    p = PROFILES[mid]
    sim = MachineSimulator(p, seed=11)
    for _ in range(120):
        sim.step()
    if fault:
        sim.inject_fault(fault, ramp_seconds=30, severity=1.0)
    rows = [sim.step() for _ in range(seconds)]
    win = [r for r in rows if r["state"] == "RUNNING"][-30:]
    return {"machineId": mid, "features": compute_features(win), "baselines": p.baseline_means()}


@pytest.fixture()
def client(trained_registry):
    return TestClient(create_app(str(trained_registry), api_key="test-key"))


def test_auth_required(client):
    assert client.post("/predict", json=payload()).status_code == 401
    assert client.get("/health").status_code == 200


def test_unconfigured_key_is_503(trained_registry):
    c = TestClient(create_app(str(trained_registry), api_key=""))
    assert c.post("/predict", json=payload(), headers=KEY).status_code == 503


def test_predict_healthy_vs_faulty(client):
    ok = client.post("/predict", json=payload(), headers=KEY).json()
    bad = client.post("/predict", json=payload("bearing_wear"), headers=KEY).json()
    assert ok["modelVersion"] == "v0001"
    assert 0 <= ok["failureProbability"] <= 1 and 0 <= ok["anomalyScore"] <= 1
    assert bad["failureProbability"] > ok["failureProbability"]
    assert bad["anomalyScore"] > ok["anomalyScore"]
    assert ok["inferenceMs"] >= 0
    top = [f["name"] for f in bad["topFeatures"]]
    assert any(n.startswith(("vibration", "temperature")) for n in top)


def test_validation_errors(client):
    p = payload()
    del p["features"]["vibration_rms"]
    assert client.post("/predict", json=p, headers=KEY).status_code == 422
    p = payload(); p["baselines"]["speed"] = 0
    assert client.post("/predict", json=p, headers=KEY).status_code == 422
    p = payload(); p["quality"] = "WEIRD"
    assert client.post("/predict", json=p, headers=KEY).status_code == 422


def test_registry_lifecycle_and_shadow(trained_registry, small_dataset, tmp_path):
    import shutil
    d = tmp_path / "reg"; shutil.copytree(trained_registry, d)
    from training import train
    ds = tmp_path / "d.csv"; small_dataset.to_csv(ds, index=False)
    v2 = train.main(["--seed", "2", "--registry", str(d), "--dataset", str(ds)])
    assert v2 == "v0002" and Registry(d).state()["versions"]["v0002"]["status"] == "CANDIDATE"
    c = TestClient(create_app(str(d), api_key="test-key"))
    assert c.post("/models/v0002/shadow", headers=KEY).status_code == 200
    r = c.post("/predict", json=payload(), headers=KEY).json()
    assert r["modelVersion"] == "v0001" and r["shadow"]["modelVersion"] == "v0002"
    assert c.post("/models/v0002/activate", headers=KEY).json()["active"] == "v0002"
    assert c.post("/predict", json=payload(), headers=KEY).json()["modelVersion"] == "v0002"
    assert c.post("/models/rollback", headers=KEY).json()["active"] == "v0001"
    assert c.post("/models/rollback", headers=KEY).status_code == 409
    states = {v["version"]: v["status"] for v in c.get("/models", headers=KEY).json()["versions"]}
    assert states == {"v0001": "ACTIVE", "v0002": "ROLLED_BACK"}
    assert c.post("/models/v9999/activate", headers=KEY).status_code == 404


def test_training_split_is_chronological_without_overlap(small_dataset):
    from training.train import chronological_split
    tr, va, te = chronological_split(small_dataset)
    for mid in small_dataset.machineId.unique():
        a, b, c = (x[x.machineId == mid] for x in (tr, va, te))
        assert a.t.max() < b.t.min() and b.t.max() < c.t.min()
        assert b.t.min() - a.t.max() >= 30   # purge gap >= one window
