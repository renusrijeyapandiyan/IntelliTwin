import math
from typing import Dict, List, Literal, Optional

from pydantic import BaseModel, Field, field_validator, model_validator

from .features import FEATURE_NAMES, SENSORS


class PredictRequest(BaseModel):
    machineId: str = Field(min_length=1, max_length=64)
    ts: Optional[str] = None
    features: Dict[str, float]
    baselines: Dict[str, float]
    quality: Literal["OK", "SUSPECT", "BAD"] = "OK"

    @model_validator(mode="after")
    def _check(self):
        missing = [f for f in FEATURE_NAMES if f not in self.features]
        if missing:
            raise ValueError(f"missing features: {missing[:5]}")
        missing_b = [s for s in SENSORS if s not in self.baselines or self.baselines[s] <= 0]
        if missing_b:
            raise ValueError(f"baselines must be present and > 0 for: {missing_b}")
        bad = [k for k, v in self.features.items() if not math.isfinite(v)]
        if bad:
            raise ValueError(f"non-finite feature values: {bad[:5]}")
        return self


class FeatureContribution(BaseModel):
    name: str
    value: float          # normalised value
    zScore: float         # deviation vs healthy training distribution
    importance: float
    contribution: float
    direction: Literal["HIGH", "LOW"]


class ShadowResult(BaseModel):
    modelVersion: str
    anomalyScore: float
    failureProbability: float


class PredictResponse(BaseModel):
    machineId: str
    modelVersion: str
    anomalyScore: float            # 0..1 normalised (calibrated on healthy training data)
    anomalyRaw: float
    failureProbability: float
    confidence: float              # |2p-1|
    topFeatures: List[FeatureContribution]
    inferenceMs: float
    shadow: Optional[ShadowResult] = None
