"""IntelliTwin AI service.  POST /predict, GET /health, model registry admin endpoints."""
from __future__ import annotations
import logging
import os
import secrets
import time
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException

from .features import normalize
from .model_store import ModelStore
from .registry import Registry
from .schemas import PredictRequest, PredictResponse, ShadowResult

log = logging.getLogger("intellitwin.ai")
logging.basicConfig(level=logging.INFO)


def create_app(registry_dir: Optional[str] = None, api_key: Optional[str] = None) -> FastAPI:
    registry_dir = registry_dir or os.getenv("MODEL_REGISTRY_DIR", "model_registry")
    key = api_key if api_key is not None else os.getenv("AI_API_KEY", "")
    registry = Registry(registry_dir)
    store = ModelStore(registry)
    app = FastAPI(title="IntelliTwin AI Service", version="1.0")
    if not key:
        log.warning("AI_API_KEY is not set: protected endpoints will return 503")

    def auth(x_api_key: str = Header(default="")):
        if not key:
            raise HTTPException(503, "AI_API_KEY not configured")
        if not secrets.compare_digest(x_api_key, key):
            raise HTTPException(401, "invalid API key")

    @app.get("/health")
    def health():
        store.refresh()
        return {"status": "ok" if store.active else "no_active_model",
                "activeModel": store.active.version if store.active else None,
                "shadowModel": store.shadow.version if store.shadow else None}

    @app.post("/predict", response_model=PredictResponse, dependencies=[Depends(auth)])
    def predict(req: PredictRequest):
        t0 = time.perf_counter()
        store.refresh()
        m = store.active
        if m is None:
            raise HTTPException(503, "no active model; run training first")
        try:
            norm = normalize(req.features, req.baselines)
        except (KeyError, ValueError) as e:
            raise HTTPException(422, str(e))
        vec = store.vector(norm)
        s = m.score(vec)
        shadow = None
        if store.shadow is not None:
            try:
                ss = store.shadow.score(vec)
                shadow = ShadowResult(modelVersion=store.shadow.version, anomalyScore=ss["anomaly"],
                                      failureProbability=ss["p"])
            except Exception:   # a broken candidate must never affect live predictions
                log.exception("shadow scoring failed")
        return PredictResponse(
            machineId=req.machineId, modelVersion=m.version, anomalyScore=s["anomaly"], anomalyRaw=s["anomaly_raw"],
            failureProbability=s["p"], confidence=abs(2 * s["p"] - 1), topFeatures=m.contributions(vec),
            inferenceMs=round((time.perf_counter() - t0) * 1000, 3), shadow=shadow)

    # ---------------- registry administration (called by the backend / learning loop) ----------------
    @app.get("/models", dependencies=[Depends(auth)])
    def models():
        return {"state": registry.state(), "versions": registry.list_versions()}

    @app.post("/models/{version}/activate", dependencies=[Depends(auth)])
    def activate(version: str):
        try:
            registry.set_active(version)
        except KeyError:
            raise HTTPException(404, f"unknown version {version}")
        store.refresh(force=True)
        return {"active": version}

    @app.post("/models/rollback", dependencies=[Depends(auth)])
    def rollback():
        try:
            v = registry.rollback()
        except ValueError as e:
            raise HTTPException(409, str(e))
        store.refresh(force=True)
        return {"active": v}

    @app.post("/models/{version}/shadow", dependencies=[Depends(auth)])
    def shadow(version: str):
        try:
            registry.set_shadow(version)
        except KeyError:
            raise HTTPException(404, f"cannot shadow {version}")
        store.refresh(force=True)
        return {"shadow": version}

    @app.delete("/models/shadow", dependencies=[Depends(auth)])
    def clear_shadow():
        registry.set_shadow(None)
        store.refresh(force=True)
        return {"shadow": None}

    return app


app = create_app() if os.getenv("AI_AUTOCREATE", "1") == "1" else None
