"""Reproduce metrics and report figures from saved real evaluation predictions."""

import argparse
from pathlib import Path

import numpy as np
from sklearn.metrics import (accuracy_score, average_precision_score, balanced_accuracy_score,
                             brier_score_loss, classification_report, confusion_matrix,
                             f1_score, log_loss, matthews_corrcoef, precision_score,
                             recall_score, roc_auc_score)

from common import MODELS, PROCESSED, REPORTS, SEED, read_json, read_jsonl, sha256, write_json


def calculate_metrics(y, probability, threshold):
    y = np.asarray(y, dtype=int)
    p = np.asarray(probability, dtype=float)
    prediction = (p >= threshold).astype(int)
    tn, fp, fn, tp = confusion_matrix(y, prediction, labels=[0, 1]).ravel()
    return {"n": len(y), "events": int(y.sum()), "event_rate": float(y.mean()),
            "roc_auc": float(roc_auc_score(y, p)),
            "average_precision": float(average_precision_score(y, p)),
            "accuracy": float(accuracy_score(y, prediction)),
            "balanced_accuracy": float(balanced_accuracy_score(y, prediction)),
            "sensitivity": float(recall_score(y, prediction, zero_division=0)),
            "specificity": float(tn / (tn + fp)),
            "precision": float(precision_score(y, prediction, zero_division=0)),
            "negative_predictive_value": float(tn / (tn + fn)) if tn + fn else None,
            "f1": float(f1_score(y, prediction, zero_division=0)),
            "mcc": float(matthews_corrcoef(y, prediction)),
            "brier": float(brier_score_loss(y, p)),
            "log_loss": float(log_loss(y, p, labels=[0, 1])),
            "threshold": float(threshold),
            "confusion_matrix": [[int(tn), int(fp)], [int(fn), int(tp)]],
            "confusion_matrix_axes": {"rows": ["Observed negative", "Observed positive"],
                                      "columns": ["Predicted negative", "Predicted positive"]},
            "classification_report": classification_report(y, prediction, labels=[0, 1],
                                                          output_dict=True, zero_division=0)}


def bootstrap_intervals(y, probability, threshold, repeats=500):
    rng = np.random.default_rng(SEED)
    y, p = np.asarray(y), np.asarray(probability)
    keys = ["roc_auc", "average_precision", "accuracy", "sensitivity", "specificity",
            "precision", "f1", "brier"]
    samples = {key: [] for key in keys}
    for _ in range(repeats):
        index = rng.integers(0, len(y), len(y))
        if len(np.unique(y[index])) < 2:
            continue
        metric = calculate_metrics(y[index], p[index], threshold)
        for key in keys:
            samples[key].append(metric[key])
    return {"method": "participant bootstrap percentile 95%; conditional on this fitted model",
            "requested_repeats": repeats, "valid_repeats": len(samples[keys[0]]),
            "intervals": {key: [float(v) for v in np.quantile(values, [.025, .975])]
                          for key, values in samples.items() if values}}


def evaluate_saved(version, bootstrap=500, qa_scripts=None):
    directory = REPORTS / "training" / version
    run = read_json(directory / "run.json")
    rows = read_jsonl(PROCESSED / "runs" / version / "predictions.jsonl")
    run["predictions_sha256"] = sha256(PROCESSED / "runs" / version / "predictions.jsonl")
    for code, disease in run["diseases"].items():
        if disease["status"] != "trained":
            continue
        for name, model in disease["models"].items():
            selected = [row for row in rows if row["disease_code"] == code
                        and row["model"] == name and row["split"] == "evaluation"]
            y = [row["label"] for row in selected]
            probability = [row["probability"] for row in selected]
            model["evaluation"] = calculate_metrics(y, probability, model["threshold"])
            model["evaluation"]["confidence_intervals"] = bootstrap_intervals(
                y, probability, model["threshold"], bootstrap)
            if model.get("operating_thresholds"):
                model["operating_points"] = {
                    policy: calculate_metrics(y, probability, threshold)
                    for policy, threshold in model["operating_thresholds"].items()}
                development = [row for row in rows if row["disease_code"] == code
                               and row["model"] == name and row["split"] == "development_oof"]
                model["development_oof_metrics"] = calculate_metrics(
                    [r["label"] for r in development], [r["probability"] for r in development], model["threshold"])
        disease["constant_baseline"] = calculate_metrics(
            y, np.full(len(y), disease["development_event_rate"]), .5)
        write_json(directory / code / "metrics.json", disease)
    write_json(directory / "run.json", run)
    if (MODELS / version / "metadata.json").exists():
        write_json(MODELS / version / "metadata.json", run)
    from plot_performance import render_report
    render_report(run, rows, directory, qa_scripts)
    print(f"Performance report: {directory / 'performance_report.html'}")
    return run


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--bootstrap", type=int, default=500)
    parser.add_argument("--qa-scripts", type=Path)
    args = parser.parse_args()
    evaluate_saved(args.version, args.bootstrap, args.qa_scripts)
