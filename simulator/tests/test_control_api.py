from fastapi.testclient import TestClient

from intellitwin_sim.control_api import create_app
from intellitwin_sim.runner import Runner


def make():
    r = Runner(["M-001", "M-002"], seed=1, interval=1.0, sink=lambda p: True)
    return r, TestClient(create_app(r))


def test_inject_status_clear():
    r, c = make()
    assert c.get("/status").json()["machines"]["M-001"]["faults"] == {}
    assert c.post("/faults", json={"machineId": "M-001", "fault": "bearing_wear", "rampSeconds": 60}).status_code == 200
    assert "bearing_wear" in r.fleet["M-001"].faults
    assert c.delete("/faults/M-001").status_code == 200
    assert not r.fleet["M-001"].faults


def test_validation_errors():
    _, c = make()
    assert c.post("/faults", json={"machineId": "M-001", "fault": "nope"}).status_code == 400
    assert c.post("/faults", json={"machineId": "X", "fault": "jam"}).status_code == 404
    assert c.post("/faults", json={"machineId": "M-001", "fault": "jam", "severity": 5}).status_code == 422
    assert c.post("/machines/M-001/explode").status_code == 400
