"""Evaluate existing weights on their actual training members and remaining test members."""

import argparse
from datetime import datetime, timezone
import importlib.metadata
from pathlib import Path
import sys

import joblib
import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
CHRONIC = HERE.parents[1]
SOURCE = CHRONIC / "experiments/overlap-demo/runs/overlap-20261017-demo"
sys.path.insert(0, str(CHRONIC / "src"))
from common import read_json, read_jsonl, sha256, write_json
from evaluate_models import bootstrap_intervals, calculate_metrics


def evaluate(name=None, qa_scripts=None, skip_figures=False):
    name = name or datetime.now(timezone.utc).strftime("nhefs5-fixed-%Y%m%dT%H%M%SZ")
    if Path(name).name != name or name in {".", ".."}:
        raise ValueError("Version must be a directory name")
    dest = HERE / "runs" / name
    if dest.exists():
        raise ValueError("Run already exists; use a new name")
    old = read_json(SOURCE / "manifest.json")
    source = CHRONIC / "data/nhefs/processed/cohorts.jsonl"
    source_hash = sha256(source)
    if source_hash != old["cohort_sha256"]:
        raise ValueError("Source cohort differs from the fitted model source")
    rows = read_jsonl(source)
    by_identity = {(r["disease_code"], r["participant_id"]): r for r in rows}
    if len(by_identity) != len(rows):
        raise ValueError("Duplicate disease/participant key")
    original_members = pd.read_csv(SOURCE / "data/split_manifest.csv", dtype={"participant_id": str})
    members = original_members[original_members.requested_overlap == old["default_overlap_ratio"]].copy()
    if members.duplicated(["disease_code", "participant_id"]).any():
        raise ValueError("Duplicate member record")
    keys = set(zip(members.disease_code, members.participant_id))
    if keys != set(by_identity):
        raise ValueError("Member manifest does not cover the source cohort exactly")
    if members.used_for_training.dtype != bool:
        raise ValueError("Training membership must be explicit booleans")
    data, reports = dest / "data", dest / "reports"
    data.mkdir(parents=True)
    reports.mkdir()
    manifest = {"version": name, "created_at_utc": datetime.now(timezone.utc).isoformat(),
                "evaluation_type": "existing_cohort_internal_train_test_evaluation",
                "weights_modified": False, "online_activation": False,
                "cohort_sha256": source_hash,
                "source_run": SOURCE.relative_to(CHRONIC).as_posix(),
                "source_manifest_sha256": sha256(SOURCE / "manifest.json"),
                "source_membership_sha256": sha256(SOURCE / "data/split_manifest.csv"),
                "split_rule": "Per disease: actual fitted members are train; all remaining source members are test",
                "scope": "Previously inspected cohort; internal evaluation, not new external validation",
                "source_sha256": {p.name: sha256(p) for p in HERE.glob("*.py")},
                "packages": {p: importlib.metadata.version(p) for p in
                             ["numpy", "pandas", "scikit-learn", "joblib", "matplotlib"]},
                "diseases": {}}
    predictions, metrics_table, membership, records, references = [], [], [], [], []
    for code, disease in old["diseases"].items():
        ratio = old["default_overlap_ratio"]
        weight = SOURCE / "models" / f"{code}--overlap{round(100*ratio):03d}.joblib"
        before = sha256(weight)
        expected = next(x for x in disease["experiments"] if x["requested_overlap"] == ratio)
        if before != expected["weights_sha256"]:
            raise ValueError("Original weight checksum differs: " + code)
        bundle = joblib.load(weight)
        columns, threshold = bundle["features"], bundle["threshold"]
        disease_members = members[members.disease_code == code]
        result = {"name": disease["name"], "features": columns, "threshold": threshold,
                  "weights_sha256": before, "metrics": {}}
        split_ids = {}
        arrays = {}
        for split, training in [("train", True), ("test", False)]:
            group = disease_members[disease_members.used_for_training == training]
            people = [by_identity[(code, ident)] for ident in group.participant_id]
            if not people:
                raise ValueError("Empty split: " + code + ":" + split)
            split_ids[split] = set(group.participant_id)
            if [r["label"] for r in people] != group.label.tolist():
                raise ValueError("Labels differ from original training manifest")
            x = pd.DataFrame([r["features"] for r in people], columns=columns).astype(float)
            y = np.array([r["label"] for r in people], dtype=int)
            prob = bundle["pipeline"].predict_proba(x)[:, 1]
            arrays[split] = (y, prob)
            metrics = calculate_metrics(y, prob, threshold)
            result["metrics"][split] = metrics
            if split == "test":
                metrics["confidence_intervals"] = bootstrap_intervals(y, prob, threshold, repeats=500)
            metrics_table.append({"disease_code": code, "disease_name": disease["name"], "split": split,
                                  **{k: v for k, v in metrics.items() if isinstance(v, (int, float))},
                                  **dict(zip(["tn", "fp", "fn", "tp"], np.array(metrics["confusion_matrix"]).ravel().tolist()))})
            for person, p in zip(people, prob):
                identity = {"disease_code": code, "participant_id": person["participant_id"], "split": split}
                membership.append(identity)
                records.append({**identity, "label": person["label"], **person["features"]})
                predictions.append({**identity, "label": person["label"], "probability": float(p),
                                    "prediction": int(p >= threshold)})
        if split_ids["train"] & split_ids["test"]:
            raise ValueError("Train and test participants must be disjoint within a disease")
        if len(split_ids["train"]) != expected["n_train"] or len(split_ids["test"]) != expected["n_unseen"]:
            raise ValueError("Member counts differ from the actual fitting record")
        for key in ["accuracy", "balanced_accuracy", "sensitivity", "precision", "f1", "roc_auc"]:
            if abs(result["metrics"]["test"][key] - expected["unseen_evaluation"][key]) > 1e-12:
                raise ValueError("Test predictions differ from original remaining-member predictions")
        y_train, _ = arrays["train"]
        y_test, _ = arrays["test"]
        result["constant_baseline"] = calculate_metrics(y_test, np.full(len(y_test), y_train.mean()), threshold)
        if sha256(weight) != before:
            raise ValueError("Weight was changed during evaluation")
        references.append({"disease_code": code, "file": weight.relative_to(CHRONIC).as_posix(),
                           "sha256": before, "threshold": threshold, "model_version": bundle["version"]})
        manifest["diseases"][code] = result
        write_json(reports / f"{code}_metrics.json", result)
        m = result["metrics"]["test"]
        print(f"{code}: train={len(y_train)}, test={len(y_test)}, events={int(y_test.sum())}, "
              f"accuracy={m['accuracy']:.2%}, recall={m['sensitivity']:.2%}, AUC={m['roc_auc']:.3f}", flush=True)
    frame = pd.DataFrame(records)
    for split in ["train", "test"]:
        frame[frame.split == split].to_csv(data / (split + ".csv"), index=False, encoding="utf-8-sig")
    pd.DataFrame(membership).to_csv(data / "split_manifest.csv", index=False, encoding="utf-8-sig")
    pd.DataFrame(predictions).to_csv(reports / "predictions.csv", index=False, encoding="utf-8-sig")
    metrics = pd.DataFrame(metrics_table)
    metrics.to_csv(reports / "metrics_summary.csv", index=False, encoding="utf-8-sig")
    metrics[metrics.split == "test"].to_csv(reports / "test_metrics.csv", index=False, encoding="utf-8-sig")
    if sha256(source) != source_hash:
        raise ValueError("Source data changed during evaluation")
    write_json(dest / "model_references.json", references)
    write_json(dest / "manifest.json", manifest)
    write_json(data / "integrity.json", {"cohort_sha256": source_hash, "source_unchanged": True,
               "weights_unchanged": True, "train_test_disjoint_per_disease": True,
               "all_source_records_accounted_for": len(records) == len(rows),
               "files_sha256": {p.name: sha256(p) for p in data.glob("*.csv")},
               "predictions_sha256": sha256(reports / "predictions.csv")})
    if not skip_figures:
        from plot_report import render
        render(dest, qa_scripts)
    return dest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name")
    parser.add_argument("--qa-scripts", type=Path)
    parser.add_argument("--skip-figures", action="store_true")
    args = parser.parse_args()
    evaluate(args.name, args.qa_scripts, args.skip_figures)
