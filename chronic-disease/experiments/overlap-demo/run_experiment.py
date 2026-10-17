"""Reproducible, explicitly contaminated evaluation for an overlap demonstration."""

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.metadata
from pathlib import Path
import shutil
import sys

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import ExtraTreesClassifier
from sklearn.impute import SimpleImputer
from sklearn.pipeline import Pipeline

HERE = Path(__file__).resolve().parent
CHRONIC = HERE.parents[1]
sys.path.insert(0, str(CHRONIC / "src"))
from audit_cohorts import FEATURE_NAMES
from common import read_json, read_jsonl, sha256, write_json
from evaluate_models import calculate_metrics

SEED = 20261017
RATIOS = [0., .25, .5, .75, 1.]
DEFAULT_RATIO = .75
REFERENCE = "nhefs5-opt-20261015-v2"
THRESHOLD = .5


def finite_metrics(y, p):
    if len(y) == 0 or len(np.unique(y)) != 2:
        return None
    return calculate_metrics(y, p, THRESHOLD)


def ordered_ids(rows, label, code):
    return sorted((r["participant_id"] for r in rows if r["label"] == label),
                  key=lambda ident: hashlib.sha256(f"{SEED}:{code}:{label}:{ident}".encode()).hexdigest())


def run(name=None, qa_scripts=None, skip_figures=False):
    name = name or datetime.now(timezone.utc).strftime("overlap-%Y%m%dT%H%M%SZ")
    if Path(name).name != name or name in {".", ".."}:
        raise ValueError("Run name must be one directory name")
    dest = HERE / "runs" / name
    if dest.exists():
        raise ValueError("Run already exists; use a new name to preserve results")
    source = CHRONIC / "data/nhefs/processed/cohorts.jsonl"
    metadata = read_json(CHRONIC / "models" / REFERENCE / "metadata.json")
    audit = read_json(CHRONIC / "reports/nhefs/cohort_audit.json")
    if sha256(source) != audit["cohorts_sha256"]:
        raise ValueError("Source cohort has changed since the audit")
    original_hash = sha256(source)
    rows = read_jsonl(source)
    identities = [(r["disease_code"], r["participant_id"]) for r in rows]
    if len(set(identities)) != len(identities):
        raise ValueError("Duplicate participant within a disease")
    data_dir, model_dir, reports = dest / "data", dest / "models", dest / "reports"
    for directory in [data_dir, model_dir, reports]:
        directory.mkdir(parents=True)
    shutil.copyfile(source, data_dir / "cohort_snapshot.jsonl")
    historical = pd.read_csv(CHRONIC / "reports/training" / REFERENCE / "metrics_summary.csv")
    historical[historical["selected_using_development"]].to_csv(
        reports / "reference_metrics.csv", index=False, encoding="utf-8-sig")
    manifest = {"version": name, "seed": SEED, "created_at_utc": datetime.now(timezone.utc).isoformat(),
                "purpose": "Explicit training/evaluation overlap demonstration; not independent validation",
                "evaluation_status": "intentionally_contaminated_demonstration",
                "eligible_for_online_activation": False,
                "cohort_sha256": original_hash, "reference_version": REFERENCE,
                "threshold": THRESHOLD, "default_overlap_ratio": DEFAULT_RATIO,
                "requested_overlap_ratios": RATIOS,
                "model": "ExtraTreesClassifier", "model_parameters": {
                    "n_estimators": 400, "max_features": 1., "min_samples_leaf": 1,
                    "class_weight": "balanced", "bootstrap": False,
                    "random_state": SEED, "n_jobs": 4},
                "threshold_rule": "Fixed 0.5, not optimized on evaluation labels",
                "packages": {n: importlib.metadata.version(n) for n in
                             ["numpy", "pandas", "scikit-learn", "joblib", "matplotlib"]},
                "source_sha256": {p.name: sha256(p) for p in HERE.glob("*.py")},
                "diseases": {}}
    summary, predictions, splits = [], [], []
    for code, old in metadata["diseases"].items():
        subset = [r for r in rows if r["disease_code"] == code]
        development = [r for r in subset if r["split"] == "development"]
        evaluation = [r for r in subset if r["split"] == "evaluation"]
        dev_ids = {r["participant_id"] for r in development}
        eval_ids = {r["participant_id"] for r in evaluation}
        assert not dev_ids & eval_ids
        columns = [f for f in FEATURE_NAMES if f != f"history_{code}"]
        assert "participant_id" not in columns and "label" not in columns
        ordered = {label: ordered_ids(evaluation, label, code) for label in [0, 1]}
        x_eval = pd.DataFrame([r["features"] for r in evaluation], columns=columns).astype(float)
        y_eval = np.asarray([r["label"] for r in evaluation], dtype=int)
        disease = {"name": old["name"], "features": columns, "n_development": len(development),
                   "n_evaluation": len(evaluation), "events_evaluation": int(y_eval.sum()), "experiments": []}
        manifest["diseases"][code] = disease
        prior = set()
        for ratio in RATIOS:
            included = set().union(*(set(ids[:round(len(ids) * ratio)]) for ids in ordered.values()))
            assert prior <= included <= eval_ids
            prior = included
            training = development + [r for r in evaluation if r["participant_id"] in included]
            train_ids = {r["participant_id"] for r in training}
            assert len(training) == len(train_ids) and train_ids & eval_ids == included
            x = pd.DataFrame([r["features"] for r in training], columns=columns).astype(float)
            y = np.asarray([r["label"] for r in training], dtype=int)
            pipeline = Pipeline([
                ("imputer", SimpleImputer(strategy="median", add_indicator=True, keep_empty_features=True)),
                ("model", ExtraTreesClassifier(**manifest["model_parameters"]))])
            pipeline.fit(x, y)
            probability = pipeline.predict_proba(x_eval)[:, 1]
            seen = np.asarray([r["participant_id"] in included for r in evaluation])
            metrics = calculate_metrics(y_eval, probability, THRESHOLD)
            unseen = finite_metrics(y_eval[~seen], probability[~seen])
            train_metrics = calculate_metrics(y, pipeline.predict_proba(x)[:, 1], THRESHOLD)
            record = {"disease_code": code, "disease_name": old["name"],
                      "requested_overlap": ratio, "actual_overlap": len(included) / len(evaluation),
                      "n_train": len(training), "n_overlap": len(included), "n_unseen": int((~seen).sum()),
                      "model": "ExtraTrees", "evaluation_status": "clean_control" if ratio == 0 else "contaminated_demo",
                      **{k: v for k, v in metrics.items() if isinstance(v, (int, float))},
                      **dict(zip(["tn", "fp", "fn", "tp"], np.asarray(metrics["confusion_matrix"]).ravel().tolist()))}
            for key in ["accuracy", "balanced_accuracy", "sensitivity", "precision", "f1", "roc_auc"]:
                record["unseen_" + key] = unseen[key] if unseen else None
            record["train_accuracy"] = train_metrics["accuracy"]
            summary.append(record)
            result = {"requested_overlap": ratio, "actual_overlap": record["actual_overlap"],
                      "n_overlap": len(included), "n_train": len(training), "n_unseen": record["n_unseen"],
                      "evaluation": metrics, "unseen_evaluation": unseen, "training_resubstitution": train_metrics}
            disease["experiments"].append(result)
            weight_name = f"{code}--overlap{round(100*ratio):03d}.joblib"
            joblib.dump({"pipeline": pipeline, "features": columns, "disease_code": code,
                         "threshold": THRESHOLD, "version": name,
                         "evaluation_status": record["evaluation_status"], "overlap_ratio": ratio,
                         "online_activation": False}, model_dir / weight_name, compress=3)
            result["weights_sha256"] = sha256(model_dir / weight_name)
            for r, p, used in zip(evaluation, probability, seen):
                predictions.append({"disease_code": code, "participant_id": r["participant_id"],
                                    "requested_overlap": ratio, "label": r["label"],
                                    "probability": float(p), "prediction": int(p >= THRESHOLD),
                                    "seen_in_training": bool(used)})
            for r in subset:
                splits.append({"disease_code": code, "participant_id": r["participant_id"],
                               "requested_overlap": ratio, "original_split": r["split"], "label": r["label"],
                               "used_for_training": r["participant_id"] in train_ids,
                               "used_for_evaluation": r["split"] == "evaluation"})
            print(f"{code} overlap={ratio:.0%}: accuracy={metrics['accuracy']:.2%}, "
                  f"recall={metrics['sensitivity']:.2%}, AUC={metrics['roc_auc']:.3f}", flush=True)
        write_json(reports / f"{code}_metrics.json", disease)
    frame = pd.DataFrame(summary)
    frame.to_csv(reports / "metrics_summary.csv", index=False, encoding="utf-8-sig")
    frame[frame.requested_overlap == DEFAULT_RATIO].to_csv(reports / "demo_metrics.csv", index=False, encoding="utf-8-sig")
    pd.DataFrame(predictions).to_csv(reports / "predictions.csv", index=False, encoding="utf-8-sig")
    pd.DataFrame(splits).to_csv(data_dir / "split_manifest.csv", index=False, encoding="utf-8-sig")
    manifest["default_demo_all_accuracy_at_least_80_percent"] = bool(
        (frame.loc[frame.requested_overlap == DEFAULT_RATIO, "accuracy"] >= .8).all())
    assert sha256(source) == original_hash
    write_json(dest / "manifest.json", manifest)
    write_json(dest / "data/integrity.json", {"original_data_unchanged": True,
               "labels_unchanged": True, "evaluation_membership_unchanged": True,
               "prediction_rows": len(predictions), "split_rows": len(splits),
               "predictions_sha256": sha256(reports / "predictions.csv"),
               "split_manifest_sha256": sha256(data_dir / "split_manifest.csv")})
    if not skip_figures:
        from plot_report import render
        render(dest, qa_scripts)
    print(f"Completed: {dest}", flush=True)
    return dest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name")
    parser.add_argument("--qa-scripts", type=Path)
    parser.add_argument("--skip-figures", action="store_true")
    args = parser.parse_args()
    run(args.name, args.qa_scripts, args.skip_figures)
