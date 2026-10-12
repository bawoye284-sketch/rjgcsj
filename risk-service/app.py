from pathlib import Path
from datetime import datetime, timedelta
from typing import List, Optional
import joblib
import numpy as np
import pandas as pd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from five_component import ASSESSMENT_BASIS, assess_observed_five, combine_risk

BASE_DIR = Path(__file__).resolve().parent
MODEL_FILE = BASE_DIR / 'model' / 'five_score_future_model.joblib'
bundle = joblib.load(MODEL_FILE) if MODEL_FILE.exists() else None
if bundle is not None and bundle.get('training_basis') != 'NUMERIC5_FUTURE_AGGREGATE_V1':
    raise RuntimeError('Online model has an unexpected training target')

app = FastAPI(title='社区老人个性化健康风险评估服务', version='1.2')

class Profile(BaseModel):
    age: int
    sex_male: int
    bmi: float

class Measurement(BaseModel):
    measured_at: Optional[str] = None
    systolic_pressure: Optional[float] = Field(default=None, ge=40, le=300)
    diastolic_pressure: Optional[float] = Field(default=None, ge=20, le=200)
    heart_rate: Optional[float] = Field(default=None, ge=30, le=220)
    spo2: Optional[float] = Field(default=None, ge=60, le=100)
    temperature: Optional[float] = Field(default=None, ge=32, le=43)
    respiratory_rate: Optional[float] = Field(default=None, ge=1, le=80)

class RiskRequest(BaseModel):
    user_id: int
    profile: Profile
    current: Optional[Measurement] = None
    history: List[Measurement] = Field(default_factory=list)


def _slope(values):
    arr = np.asarray([x for x in values if x is not None and np.isfinite(x)], dtype=float)
    if len(arr) < 3:
        return 0.0
    x = np.arange(len(arr), dtype=float)
    return float(np.polyfit(x, arr, 1)[0])


def _metric_change(history, current_value, name):
    vals = []
    for x in history[-30:]:
        v = getattr(x, name, None)
        if v is not None and np.isfinite(v):
            vals.append(float(v))
    if current_value is None or len(vals) < 3:
        return None

    mean = float(np.mean(vals))
    std = float(np.std(vals, ddof=1))
    if std < 1e-6:
        z = 0.0 if abs(current_value - mean) < 1e-6 else 3.0
    else:
        z = float((current_value - mean) / std)

    last7 = vals[-7:]
    slope7 = _slope(last7)

    z_score = min(abs(z) / 3.0, 1.0)
    scale = max(abs(mean), 1.0)
    trend_score = min(abs(slope7) / (0.015 * scale), 1.0)
    change_score = 0.75 * z_score + 0.25 * trend_score

    return {
        'metric': name,
        'mean_30d': round(mean, 3),
        'std_30d': round(std, 3),
        'current': round(float(current_value), 3),
        'z_score': round(z, 3),
        'slope_7': round(slope7, 4),
        'change_score': round(float(change_score), 4)
    }


def _recent_history(req: RiskRequest):
    """Use up to 30 prior measurements within 30 days of the current one."""
    if req.current is None or not req.current.measured_at:
        return []
    try:
        current_time = datetime.fromisoformat(req.current.measured_at)
    except ValueError:
        return []
    start_time = current_time - timedelta(days=30)
    entries = []
    for entry in req.history:
        if not entry.measured_at:
            continue
        try:
            measured = datetime.fromisoformat(entry.measured_at)
            if start_time <= measured < current_time:
                entries.append((measured, entry))
        except (TypeError, ValueError):
            continue
    entries.sort(key=lambda item: item[0])
    return [entry for _, entry in entries[-30:]]


def personal_change(req: RiskRequest):
    recent_history = _recent_history(req)
    if len(recent_history) < 3:
        return 0.0, [], False

    details = []
    for name in ['systolic_pressure', 'diastolic_pressure', 'heart_rate', 'spo2', 'temperature', 'respiratory_rate']:
        current_value = getattr(req.current, name, None)
        d = _metric_change(recent_history, current_value, name)
        if d:
            details.append(d)

    if not details:
        return 0.0, [], False

    scores = sorted([x['change_score'] for x in details], reverse=True)
    score = float(np.mean(scores[:2]))
    return round(score, 4), details, True


def build_reasons(model_score, model_threshold, change_score, baseline_available, details, observed):
    reasons = []
    if model_score >= model_threshold:
        reasons.append('模型提示后续五项生理指标综合分可能达到预警区间')
    if baseline_available and change_score >= 0.50:
        reasons.append('近期监测数据明显偏离个人历史常态')
    if observed['numeric_total'] is not None and observed['numeric_total'] >= 5:
        reasons.append('本次多项生理指标处于预警区间')

    important = sorted(details, key=lambda x: x['change_score'], reverse=True)[:2]
    name_map = {
        'systolic_pressure': '收缩压',
        'diastolic_pressure': '舒张压',
        'heart_rate': '心率',
        'spo2': '血氧',
        'temperature': '体温',
        'respiratory_rate': '呼吸频率',
    }
    for d in important:
        if d['change_score'] >= 0.45:
            reasons.append(
                f'{name_map.get(d["metric"], d["metric"])}相对近30天个人基线变化明显'
            )
    return reasons[:6]


def model_feature_row(req: RiskRequest):
    """Match first-day means where possible; single reading is the fallback."""
    current = req.current
    if current is None:
        return None
    recent = [current]
    try:
        last_time = datetime.fromisoformat(current.measured_at)
        for entry in req.history:
            if entry.measured_at:
                measured = datetime.fromisoformat(entry.measured_at)
                if last_time - timedelta(hours=24) <= measured <= last_time:
                    recent.append(entry)
    except (TypeError, ValueError):
        pass

    def average(field):
        values = [float(getattr(item, field)) for item in recent
                  if getattr(item, field, None) is not None and np.isfinite(getattr(item, field))]
        return float(np.mean(values)) if values else np.nan

    return {
        'age': req.profile.age,
        'sex': req.profile.sex_male,
        'bmi': req.profile.bmi,
        'sbp_mean': average('systolic_pressure'),
        'dbp_mean': average('diastolic_pressure'),
        'heart_rate_mean': average('heart_rate'),
        'spo2_mean': average('spo2'),
        'resp_rate_mean': average('respiratory_rate'),
        'temperature_mean': average('temperature'),
    }


def predict_model_score(req: RiskRequest) -> float:
    row = model_feature_row(req)
    if row is None:
        raise HTTPException(status_code=422, detail='请先保存本次健康测量')
    features = pd.DataFrame([row], columns=bundle['features'])
    transformed = bundle['imputer'].transform(features)
    return float(bundle['model'].predict_proba(transformed)[0, 1])


@app.get('/health')
def health():
    return {
        'ok': bundle is not None,
        'model_ready': bundle is not None,
        'model_name': bundle.get('model_name') if bundle else None,
        'model_version': bundle.get('model_version') if bundle else None,
        'threshold': bundle.get('threshold') if bundle else None,
        'training_basis': bundle.get('training_basis') if bundle else None,
    }


@app.post('/predict')
def predict(req: RiskRequest):
    if bundle is None:
        raise HTTPException(status_code=503, detail='五项生理指标模型文件不存在，请先训练并部署模型')

    model_score = predict_model_score(req)

    change_score, details, baseline_available = personal_change(req)
    observed = assess_observed_five(req.current)
    threshold = float(bundle['threshold'])
    level = combine_risk(model_score, threshold, change_score, baseline_available, observed)
    reasons = build_reasons(model_score, threshold, change_score, baseline_available, details, observed)

    return {
        'user_id': req.user_id,
        # Keep this wire name for the existing Java storage column; it now
        # contains the future five-component aggregate-warning probability.
        'group_risk_probability': round(model_score, 4),
        'model_threshold': round(threshold, 4),
        'personal_change_score': round(change_score, 4),
        'baseline_available': baseline_available,
        'risk_level': level,
        'news2_complete': observed['complete'],
        'single_alerts': observed['single_alerts'],
        'assessment_basis': ASSESSMENT_BASIS,
        'reasons': reasons,
        'baseline_details': details,
        'model_name': bundle['model_name'],
        'model_version': bundle['model_version'],
        'note': '实验模型预测后续五项指标日代表值综合分是否达到5分，并结合个人近期变化；不包含吸氧和意识状态，不是完整NEWS2、健康诊断或社区人群验证。'
    }
