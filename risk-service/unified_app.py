"""One local process serves both existing model families without changing their weights."""

from contextlib import asynccontextmanager
from pathlib import Path
import os
import sys

from fastapi import FastAPI

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "chronic-disease" / "src"))
os.environ.setdefault("CHRONIC_MODEL_VERSION", "nhefs5-opt-20261015-v2")

import app as personalized_api
import api as disease_api


@asynccontextmanager
async def lifespan(_app):
    async with disease_api.app.router.lifespan_context(disease_api.app):
        yield


app = FastAPI(title="健康风险联合模型服务", version="2.0.0", lifespan=lifespan,
              description="同一服务加载个性化生理预警和五病种研究权重，预测结果与阈值分别保留。")


@app.get("/health")
def health():
    personal = personalized_api.health()
    chronic = disease_api.health()
    return {**personal, "ok": personal["ok"] and chronic["ok"],
            "service": "unified-health-risk", "personalized": personal, "chronic": chronic,
            "chronic_model_ready": chronic["ok"]}


@app.get("/models")
def models():
    return {**disease_api.models(), "service": "unified-health-risk",
            "personalized_model": personalized_api.health()}


app.add_api_route("/predict", personalized_api.predict, methods=["POST"], name="个性化生理风险预测")
app.add_api_route("/predict/disease", disease_api.predict, methods=["POST"], name="五病种风险预测")
app.add_api_route("/health/chronic", disease_api.health, methods=["GET"], name="慢性病模型状态")
