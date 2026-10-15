"""Train five independent, calibrated research models on audited NHEFS data."""

import argparse
from datetime import datetime, timezone
import importlib.metadata
from pathlib import Path
import platform

import joblib
import numpy as np
import pandas as pd
from sklearn.calibration import CalibratedClassifierCV
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, brier_score_loss, roc_curve
from sklearn.model_selection import StratifiedKFold, cross_val_predict
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from xgboost import XGBClassifier

from audit_cohorts import FEATURE_NAMES, LABEL_CONFIG
from common import (MODELS, PROCESSED, REPORTS, ROOT, SEED, TARGET_CONFIG,
                    read_json, read_jsonl, sha256, targets, write_json, write_jsonl)
from evaluate_models import evaluate_saved
from optimization import RECIPE_PATH, fit_family


def estimator(family, parameters):
    if family == "LogisticRegression":
        model = LogisticRegression(max_iter=2000, random_state=SEED, **parameters)
    else:
        model = XGBClassifier(objective="binary:logistic", tree_method="hist", n_jobs=1,
                              random_state=SEED, eval_metric="logloss", **parameters)
    return Pipeline([("imputer", SimpleImputer(strategy="median", add_indicator=True,
                                                keep_empty_features=True)),
                     ("scale", StandardScaler()), ("model", model)])


def threshold_for_recall(y, p, target=.70):
    fpr, tpr, thresholds = roc_curve(y, p)
    valid = np.flatnonzero((tpr >= target) & np.isfinite(thresholds))
    best = min(valid, key=lambda i: (fpr[i], -thresholds[i]))
    return float(thresholds[best])


def run_training(version=None, bootstrap=500, qa_scripts=None, recipe="optimized", activate=False):
    version = version or datetime.now(timezone.utc).strftime(
        "nhefs5-opt-%Y%m%dT%H%M%SZ" if recipe == "optimized" else "nhefs5-%Y%m%dT%H%M%SZ")
    if Path(version).name != version or version in {".", ".."}:
        raise ValueError("Version must be a single directory name")
    destination = MODELS / version
    report_dir = REPORTS / "training" / version
    if destination.exists() or report_dir.exists():
        raise ValueError("Version already exists; use a new version to preserve previous results")
    audit = read_json(REPORTS / "nhefs" / "cohort_audit.json")
    configuration = read_json(TARGET_CONFIG)
    if not configuration["training_enabled"] or not set(configuration["first_version_target_codes"]).issubset(configuration.get("reviewed_training_target_codes", [])):
        raise ValueError("Selected targets must pass explicit codebook review before training")
    if audit["label_rules_sha256"] != sha256(LABEL_CONFIG):
        raise ValueError("Label rules changed after audit")
    if audit["cohorts_sha256"] != sha256(PROCESSED / "cohorts.jsonl"):
        raise ValueError("Dataset changed after audit")
    if audit["config_sha256"] != sha256(TARGET_CONFIG):
        raise ValueError("Target configuration changed after audit")
    if audit.get("feature_schema_sha256") != sha256(ROOT / "config" / "nhefs_feature_schema.json"):
        raise ValueError("Feature schema changed after audit; regenerate cohorts first")
    all_rows = read_jsonl(PROCESSED / "cohorts.jsonl")
    training_recipe = read_json(RECIPE_PATH) if recipe == "optimized" else None
    if training_recipe:
        reference = PROCESSED / "runs" / training_recipe["reference_version"] / "cohort_snapshot.jsonl"
        if not reference.exists():
            raise ValueError("Optimized comparison requires the frozen reference cohort snapshot")
        identity = lambda row: (row["participant_id"], row["disease_code"], row["split"], row["label"])
        original = read_jsonl(reference)
        if sorted(map(identity, original)) != sorted(map(identity, all_rows)):
            raise ValueError("Comparison cohort membership/labels changed; inspect audit before training")
    source_hashes = {p.name: sha256(p) for p in (ROOT / "src").glob("*.py")}
    run = {"version": version, "created_at_utc": datetime.now(timezone.utc).isoformat(),
           "task": audit["task"], "research_only": True, "externally_validated": False,
           "seed": SEED, "python": platform.python_version(),
           "packages": {name: importlib.metadata.version(name) for name in
                        ["numpy", "pandas", "scikit-learn", "xgboost", "joblib", "matplotlib"]},
           "audit_sha256": sha256(REPORTS / "nhefs" / "cohort_audit.json"),
           "cohorts_sha256": audit["cohorts_sha256"], "source_sha256": source_hashes,
           "selection_rule": "highest development calibrated out-of-fold average precision; Brier tie-break",
           "threshold_rule": "development calibrated out-of-fold sensitivity >=0.70, maximize specificity",
           "evaluation_rule": "ID-hash held-out participants, no tuning/calibration/selection on evaluation",
           "notes": audit["notes"], "diseases": {}}
    run["recipe"] = recipe
    run["feature_schema_sha256"] = sha256(ROOT / "config" / "nhefs_feature_schema.json")
    if training_recipe:
        run.update({"optimization": training_recipe, "recipe_sha256": sha256(RECIPE_PATH),
                    "selection_rule": training_recipe["family_selection"],
                    "threshold_rule": training_recipe["threshold_policies"][training_recipe["default_threshold_policy"]],
                    "validation_status": training_recipe["validation_status"],
                    "evaluation_rule": "Frozen original ID-hash evaluation set; previously inspected, exploratory comparison only",
                    "reference_cohort_unchanged": True})
        run["notes"] = [*run["notes"],
                        "Evaluation results were previously inspected; this is an exploratory optimization, not fresh independent validation.",
                        "Outer development folds exclude parameter/feature search and calibration; preprocessing remains inside each fit."]
    predictions = []
    grids = {
        "LogisticRegression": [{"C": value} for value in [.1, 1., 10.]],
        "XGBoost": [{"max_depth": depth, "n_estimators": count, "learning_rate": .04,
                     "min_child_weight": 5, "reg_lambda": 5., "subsample": .85,
                     "colsample_bytree": .85} for depth in [1, 2] for count in [100, 200]],
    }
    for target in targets():
        code = target["code"]
        rows = [row for row in all_rows if row["disease_code"] == code]
        development = [row for row in rows if row["split"] == "development"]
        evaluation = [row for row in rows if row["split"] == "evaluation"]
        assert not {r["participant_id"] for r in development} & {r["participant_id"] for r in evaluation}
        dev_events = sum(row["label"] for row in development)
        eval_events = sum(row["label"] for row in evaluation)
        disease = {"name": target["name_zh"], "status": "skipped", "models": {},
                   "n_development": len(development), "events_development": dev_events,
                   "n_evaluation": len(evaluation), "events_evaluation": eval_events}
        run["diseases"][code] = disease
        if min(dev_events, len(development) - dev_events) < 20 or min(eval_events, len(evaluation) - eval_events) < 5:
            disease["reason"] = "Insufficient events/non-events for 3-fold development and held-out evaluation"
            continue
        features = [name for name in FEATURE_NAMES if name != f"history_{code}"]
        if not training_recipe:
            features = [name for name in features if name in read_json(RECIPE_PATH)["feature_sets"]["original"]]
        x_dev = pd.DataFrame([row["features"] for row in development], columns=features).astype(float)
        x_eval = pd.DataFrame([row["features"] for row in evaluation], columns=features).astype(float)
        y_dev = np.asarray([row["label"] for row in development])
        y_eval = np.asarray([row["label"] for row in evaluation])
        splitter = StratifiedKFold(3, shuffle=True, random_state=SEED)
        inner = StratifiedKFold(3, shuffle=True, random_state=SEED + 1)
        family_predictions = {}
        for family, grid in grids.items():
            if training_recipe:
                fitted, p_oof, info, fold_log = fit_family(
                    family, code, x_dev, y_dev, [r["participant_id"] for r in development], training_recipe)
                p_eval = fitted.predict_proba(x_eval)[:, 1]
                disease["models"][family] = info
                info["features"] = info["parameters"]["columns__transformers"][0][2]
                family_predictions[family] = (fitted, p_oof, p_eval)
                write_json(PROCESSED / "runs" / version / f"{code}--{family}--nested_folds.json", fold_log)
                info["nested_fold_audit_sha256"] = sha256(PROCESSED / "runs" / version / f"{code}--{family}--nested_folds.json")
                for split, source, probabilities in [("development_oof", development, p_oof),
                                                       ("evaluation", evaluation, p_eval)]:
                    predictions.extend({"participant_id": row["participant_id"], "disease_code": code,
                                        "model": family, "split": split, "label": row["label"],
                                        "probability": float(probability), "threshold": info["threshold"]}
                                       for row, probability in zip(source, probabilities))
                continue
            search = []
            for parameters in grid:
                p = cross_val_predict(estimator(family, parameters), x_dev, y_dev,
                                      cv=splitter, method="predict_proba", n_jobs=1)[:, 1]
                search.append({"parameters": parameters, "average_precision": float(average_precision_score(y_dev, p)),
                               "brier": float(brier_score_loss(y_dev, p))})
            best = max(search, key=lambda item: (item["average_precision"], -item["brier"]))
            calibrated = CalibratedClassifierCV(estimator(family, best["parameters"]),
                                                method="sigmoid", cv=inner, n_jobs=1)
            # Outer folds exclude each observation from the base fits and their calibrators.
            p_oof = cross_val_predict(calibrated, x_dev, y_dev, cv=splitter,
                                      method="predict_proba", n_jobs=1)[:, 1]
            threshold = threshold_for_recall(y_dev, p_oof)
            calibrated.fit(x_dev, y_dev)
            p_eval = calibrated.predict_proba(x_eval)[:, 1]
            info = {"parameters": best["parameters"], "search": search, "calibration": "sigmoid, 3-fold ensemble",
                    "development_oof_average_precision": float(average_precision_score(y_dev, p_oof)),
                    "development_oof_brier": float(brier_score_loss(y_dev, p_oof)),
                    "threshold": threshold}
            disease["models"][family] = info
            family_predictions[family] = (calibrated, p_oof, p_eval)
            for split, source, probabilities in [("development_oof", development, p_oof),
                                                  ("evaluation", evaluation, p_eval)]:
                predictions.extend({"participant_id": row["participant_id"], "disease_code": code,
                                    "model": family, "split": split, "label": row["label"],
                                    "probability": float(probability), "threshold": threshold}
                                   for row, probability in zip(source, probabilities))
        chosen = max(disease["models"], key=lambda name: (
            disease["models"][name].get("development_oof_roc_auc", 0) if training_recipe else 0,
            disease["models"][name]["development_oof_average_precision"],
            -disease["models"][name]["development_oof_brier"]))
        disease.update({"status": "trained", "selected_model": chosen, "features": features,
                        "age_min": int(x_dev.age.min()), "age_max": int(x_dev.age.max()),
                        "outcome_definition": "Survivor-conditional new doctor-told disease report after baseline calendar year",
                        "horizon_definition": "1987-to-1992 survey interval; not an exact 60-month incidence estimate",
                        "followup_months_range": [audit["diseases"][code]["followup_months_min"],
                                                  audit["diseases"][code]["followup_months_max"]],
                        "development_event_rate": float(y_dev.mean())})
        destination.mkdir(parents=True, exist_ok=True)
        for family, (model, _, _) in family_predictions.items():
            output = destination / f"{code}--{family}.joblib"
            joblib.dump({"model": model, "features": disease["models"][family].get("features", features), "disease_code": code,
                         "version": version, "threshold": disease["models"][family]["threshold"],
                         "threshold_policy": disease["models"][family].get("threshold_policy", "screening70"),
                         "operating_thresholds": disease["models"][family].get("operating_thresholds", {}),
                         "research_only": True}, output)
            disease["models"][family]["artifact"] = output.name
            disease["models"][family]["artifact_sha256"] = sha256(output)
        print(f"{code}: development {len(development)}/{dev_events} events; evaluation {len(evaluation)}/{eval_events}; selected {chosen}", flush=True)
    write_json(report_dir / "run.json", run)
    write_jsonl(PROCESSED / "runs" / version / "cohort_snapshot.jsonl", all_rows)
    write_json(report_dir / "configuration.json", {"targets": configuration,
                                                   "label_rules": read_json(LABEL_CONFIG),
                                                   "audit": audit, "training_recipe": training_recipe,
                                                   "feature_schema": read_json(ROOT / "config" / "nhefs_feature_schema.json")})
    write_jsonl(PROCESSED / "runs" / version / "predictions.jsonl", predictions)
    run = evaluate_saved(version, bootstrap, qa_scripts)
    write_json(destination / "metadata.json", run)
    if activate:
        write_json(MODELS / "active_model.json", {"version": version, "research_only": True})
    return version


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version")
    parser.add_argument("--bootstrap", type=int, default=500)
    parser.add_argument("--qa-scripts", type=Path)
    parser.add_argument("--recipe", choices=["baseline", "optimized"], default="optimized")
    parser.add_argument("--activate", action="store_true", help="Explicitly replace the active research model pointer")
    args = parser.parse_args()
    run_training(args.version, args.bootstrap, args.qa_scripts, args.recipe, args.activate)
