"""Load versioned research models without referencing the existing risk service."""

import joblib
import pandas as pd

from common import MODELS, enough_profile_information, read_json, sha256, targets


class DiseasePredictor:
    def __init__(self, version=None):
        self.targets = targets()
        self.version = version
        self.metadata = {"diseases": {}}
        self.models = {}
        if self.version is None and (MODELS / "active_model.json").exists():
            self.version = read_json(MODELS / "active_model.json")["version"]
        if self.version:
            if self.version in {".", ".."} or "/" in self.version or "\\" in self.version:
                raise ValueError("Invalid model version")
            self.metadata = read_json(MODELS / self.version / "metadata.json")
            for code, entry in self.metadata["diseases"].items():
                if entry["status"] != "trained":
                    continue
                info = entry["models"][entry["selected_model"]]
                artifact = MODELS / self.version / info["artifact"]
                if artifact.parent != MODELS / self.version or sha256(artifact) != info["artifact_sha256"]:
                    raise ValueError("Model artifact path or checksum mismatch")
                self.models[code] = joblib.load(artifact)

    def health(self):
        return {"ok": len(self.models) == len(self.targets), "model_version": self.version,
                "ready_count": len(self.models), "expected_count": len(self.targets),
                "validation_status": self.metadata.get("validation_status", "internal_holdout_only"),
                "ready_diseases": sorted(self.models), "research_only": True,
                "external_validation": False,
                "task": "new reports among alive followup interview completers"}

    def predict(self, profile, history):
        results = []
        for target in self.targets:
            code = target["code"]
            entry = self.metadata["diseases"].get(code, {})
            result = {"disease_code": code, "disease_name": target["name_zh"], "probability": None,
                      "risk_band": None, "threshold": None, "model_version": self.version,
                      "outcome_definition": entry.get("outcome_definition"),
                      "horizon_definition": entry.get("horizon_definition"),
                      "research_only": True, "missing_fields": []}
            result.update(alert=None, threshold_policy=None)
            if history.get(code) == 1:
                result.update(status="already_diagnosed", reason="已有该病史，不进行首次新报告风险预测")
            elif history.get(code) != 0:
                result.update(status="insufficient_data", reason="需要明确该病起点病史",
                              missing_fields=[f"disease_history.{code}"])
            elif code not in self.models:
                result.update(status="model_unavailable", reason="该病种模型尚不可用")
            elif not entry["age_min"] <= profile["age"] <= entry["age_max"]:
                result.update(status="not_applicable", reason=f"研究模型适用年龄为{entry['age_min']}至{entry['age_max']}岁")
            elif profile.get("sex_male") is None:
                result.update(status="insufficient_data", reason="需要提供性别编码", missing_fields=["profile.sex_male"])
            else:
                row = {**profile, **{f"history_{key}": value for key, value in history.items()}}
                bundle = self.models[code]
                values = {name: row.get(name) for name in bundle["features"]}
                missing = [name for name, value in values.items() if value is None]
                if not enough_profile_information(profile):
                    result.update(status="insufficient_data", reason="健康与生活习惯资料过少", missing_fields=missing)
                else:
                    frame = pd.DataFrame([values], columns=bundle["features"]).astype(float)
                    probability = float(bundle["model"].predict_proba(frame)[0, 1])
                    result.update(status="predicted", probability=probability,
                                  threshold=bundle["threshold"], missing_fields=missing,
                                  threshold_policy=bundle.get("threshold_policy", "screening70"),
                                  operating_thresholds=bundle.get("operating_thresholds", {}),
                                  alert=probability >= bundle["threshold"],
                                  reason="历史美国随访生存者研究模型；可用缺失字段由训练折内拟合的处理器处理")
            results.append(result)
        return {"model_version": self.version, "research_only": True, "results": results,
                "validation_status": self.metadata.get("validation_status", "internal_holdout_only")}

    def model_information(self):
        diseases = []
        for target in self.targets:
            entry = self.metadata["diseases"].get(target["code"], {})
            model = entry.get("models", {}).get(entry.get("selected_model"), {})
            metric = model.get("evaluation", {})
            diseases.append({"code": target["code"], "name": target["name_zh"],
                             "ready": target["code"] in self.models,
                             "age_min": entry.get("age_min"), "age_max": entry.get("age_max"),
                             "features": self.models.get(target["code"], {}).get("features", []),
                             "threshold_policy": model.get("threshold_policy", "screening70"),
                             "metrics": {key: metric.get(key) for key in
                                         ["n", "events", "roc_auc", "average_precision", "sensitivity", "specificity", "precision"]},
                             "quality_note": "本版本评价中未检出阳性病例，未达到阈值不能解释为安全。"
                             if metric.get("sensitivity") == 0 else "研究模型，区分能力与检出率有限，未在中国社区老人中验证。"})
        return {**self.health(), "diseases": diseases}
