"""Small control API used (via the backend proxy) by the dashboard's simulator panel. Demo only."""
import os
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .faults import FAULT_TYPES
from .runner import Runner


class FaultRequest(BaseModel):
    machineId: str
    fault: str
    rampSeconds: Optional[float] = Field(default=None, gt=0, le=7200)
    severity: float = Field(default=1.0, ge=0, le=1)


def create_app(runner: Runner) -> FastAPI:
    app = FastAPI(title="IntelliTwin Simulator Control", version="1.0")
    token = os.getenv("CONTROL_TOKEN", "")

    def auth(x_control_token: str = Header(default="")):
        if token and x_control_token != token:
            raise HTTPException(401, "bad control token")

    def machine(mid: str):
        m = runner.fleet.get(mid)
        if m is None:
            raise HTTPException(404, f"unknown machine {mid}")
        return m

    @app.get("/health")
    def health():
        return {"status": "ok", "ticks": runner.ticks}

    @app.get("/status", dependencies=[Depends(auth)])
    def status():
        return {"faultTypes": FAULT_TYPES, "machines": runner.status()}

    @app.post("/faults", dependencies=[Depends(auth)])
    def inject(req: FaultRequest):
        m = machine(req.machineId)
        with runner.lock:
            try:
                m.inject_fault(req.fault, req.rampSeconds, req.severity)
            except ValueError as e:
                raise HTTPException(400, str(e))
        return {"ok": True, "status": runner.status()[req.machineId]}

    @app.delete("/faults/{machine_id}", dependencies=[Depends(auth)])
    def clear(machine_id: str, fault: Optional[str] = None):
        m = machine(machine_id)
        with runner.lock:
            m.clear_fault(fault)
        return {"ok": True, "status": runner.status()[machine_id]}

    @app.post("/machines/{machine_id}/{action}", dependencies=[Depends(auth)])
    def power(machine_id: str, action: str):
        m = machine(machine_id)
        if action not in ("start", "stop"):
            raise HTTPException(400, "action must be start or stop")
        with runner.lock:
            m.start() if action == "start" else m.stop()
        return {"ok": True, "status": runner.status()[machine_id]}

    return app
