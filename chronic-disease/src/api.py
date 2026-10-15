"""Independent localhost disease-risk API; uses its own models and environment."""

from contextlib import asynccontextmanager
from datetime import datetime, timezone
import os
from typing import Literal

from fastapi import FastAPI
from pydantic import BaseModel, ConfigDict, Field, field_validator

from predict import DiseasePredictor


class Profile(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    age: int = Field(ge=0, le=110)
    sex_male: Literal[0, 1] | None = None
    weight_kg: float | None = Field(default=None, ge=25, le=320)
    self_rated_health: int | None = Field(default=None, ge=1, le=5)
    smoking_current: Literal[0, 1] | None = None
    drinking_any: Literal[0, 1] | None = None
    exercise_regular: Literal[0, 1] | None = None
    high_cholesterol_history: Literal[0, 1] | None = None
    health_worsened: Literal[0, 1] | None = None
    weight_gain_10lb: Literal[0, 1] | None = None
    weight_loss_10lb: Literal[0, 1] | None = None
    hip_fracture_history: Literal[0, 1] | None = None

    @field_validator("weight_loss_10lb")
    @classmethod
    def consistent_weight_change(cls, value, info):
        if value == 1 and info.data.get("weight_gain_10lb") == 1:
            raise ValueError("Weight cannot both increase and decrease by 10 lb over the same 12 months")
        return value


class PredictionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    profile: Profile
    disease_history: dict[str, Literal[0, 1] | None] = Field(default_factory=dict)

    @field_validator("disease_history")
    @classmethod
    def validate_diseases(cls, value):
        allowed = {"hypertension", "diabetes", "stroke", "heart_attack", "osteoporosis"}
        if set(value) - allowed:
            raise ValueError("Unsupported disease code")
        return value


@asynccontextmanager
async def lifespan(app):
    app.state.predictor = DiseasePredictor(os.environ.get("CHRONIC_MODEL_VERSION"))
    yield


app = FastAPI(title="五病种风险研究服务", version="1.0.0", lifespan=lifespan,
              description="历史美国完成随访生存者的新报告预测原型，未在中国社区人群中独立验证。")


@app.get("/health")
def health():
    return app.state.predictor.health()


@app.get("/models")
def models():
    predictor = app.state.predictor
    return {**predictor.model_information(), "input_schema": PredictionRequest.model_json_schema()}


@app.post("/predict/disease")
def predict(request: PredictionRequest):
    result = app.state.predictor.predict(request.profile.model_dump(), request.disease_history)
    result["assessed_at"] = datetime.now(timezone.utc).isoformat()
    return result
