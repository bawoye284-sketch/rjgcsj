"""Paired exploratory comparison with a frozen, previously inspected run."""

import csv

import numpy as np
from sklearn.metrics import roc_auc_score

from common import PROCESSED, REPORTS, SEED, read_json, read_jsonl, write_json
from evaluate_models import calculate_metrics
from optimization import operating_thresholds


def write_comparison(run, rows, directory):
    reference_version = run["optimization"]["reference_version"]
    reference = read_json(REPORTS / "training" / reference_version / "run.json")
    original_rows = read_jsonl(PROCESSED / "runs" / reference_version / "predictions.jsonl")
    table, details = [], {}
    lines = ["# 优化前后性能对比", "", f"原版：{reference_version}；本轮：{run['version']}。", "",
             "同一病种、相同人员和标签、原ID划分。原留出集此前已查看，本轮结果为探索性对比，不能称为新独立验证，也不据此重新选择参数、模型或阈值。", "",
             "本轮默认specificity80阈值只由嵌套开发集折外预测确定：在开发集特异度至少80%的范围内最大化Youden指数。原版优先追求70%召回，因此准确率变化同时包含阈值取舍；AUC/AP用于观察排序变化。", "",
             "| 病种 | 原AUC | 新AUC | AUC变化95%配对区间 | 原准确率 | 新准确率 | 原召回率 | 新召回率 | 原误报 | 新误报 |",
             "| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for code, disease in run["diseases"].items():
        if disease["status"] != "trained":
            continue
        old = reference["diseases"][code]
        old_family, new_family = old["selected_model"], disease["selected_model"]

        def subset(source, family, split):
            return sorted([r for r in source if r["disease_code"] == code
                           and r["model"] == family and r["split"] == split], key=lambda r: r["participant_id"])

        before, after = subset(original_rows, old_family, "evaluation"), subset(rows, new_family, "evaluation")
        if [(r["participant_id"], r["label"]) for r in before] != [(r["participant_id"], r["label"]) for r in after]:
            raise ValueError("Cannot compare different participants or labels")
        y = np.array([r["label"] for r in after])
        old_p, new_p = (np.array([r["probability"] for r in sample]) for sample in [before, after])
        old_dev = subset(original_rows, old_family, "development_oof")
        old_policies = operating_thresholds(np.array([r["label"] for r in old_dev]),
                                           np.array([r["probability"] for r in old_dev]))
        old_metric = old["models"][old_family]["evaluation"]
        new_metric = disease["models"][new_family]["evaluation"]
        old_specificity80 = calculate_metrics(y, old_p, old_policies["specificity80"])
        new_screening70 = disease["models"][new_family]["operating_points"]["screening70"]
        rng, differences = np.random.default_rng(SEED), []
        for _ in range(500):
            index = rng.integers(0, len(y), len(y))
            if len(np.unique(y[index])) == 2:
                differences.append(roc_auc_score(y[index], new_p[index]) - roc_auc_score(y[index], old_p[index]))
        lower, upper = [float(v) for v in np.quantile(differences, [.025, .975])]
        row = {"disease_code": code, "disease_name": disease["name"], "old_model": old_family, "new_model": new_family,
               "old_auc": old_metric["roc_auc"], "new_auc": new_metric["roc_auc"],
               "auc_change": new_metric["roc_auc"] - old_metric["roc_auc"],
               "auc_change_ci95_lower": lower, "auc_change_ci95_upper": upper,
               "old_accuracy": old_metric["accuracy"], "new_accuracy": new_metric["accuracy"],
               "old_recall": old_metric["sensitivity"], "new_recall": new_metric["sensitivity"],
               "old_fp": old_metric["confusion_matrix"][0][1], "new_fp": new_metric["confusion_matrix"][0][1],
               "old_ap": old_metric["average_precision"], "new_ap": new_metric["average_precision"],
               "old_model_at_specificity80_accuracy": old_specificity80["accuracy"],
               "old_model_at_specificity80_recall": old_specificity80["sensitivity"],
               "new_model_at_screening70_accuracy": new_screening70["accuracy"],
               "new_model_at_screening70_recall": new_screening70["sensitivity"]}
        table.append(row)
        details[code] = {"old_model_at_specificity80": old_specificity80,
                         "new_model_at_screening70": new_screening70,
                         "paired_auc_bootstrap": {"requested_repeats": 500, "valid_repeats": len(differences),
                                                  "ci95": [lower, upper], "conditional_on_fixed_models": True}}
        lines.append(f"| {disease['name']} | {row['old_auc']:.3f} | {row['new_auc']:.3f} | {row['auc_change']:+.3f} [{lower:+.3f}, {upper:+.3f}] | {row['old_accuracy']:.2%} | {row['new_accuracy']:.2%} | {row['old_recall']:.2%} | {row['new_recall']:.2%} | {row['old_fp']} | {row['new_fp']} |")
    lines += ["", "## 如何解释", "",
              "- 准确率提高但召回下降，说明误报/漏报取舍改变，不能单凭准确率声称预测能力增强。",
              "- AUC变化区间跨0时，当前数据不足以说明改善稳定。配对区间使用同一参与者重抽样500次，未包含模型重新开发的不确定性。",
              "- CSV同时保留原模型采用specificity80策略，以及新模型采用screening70策略的准确率/召回率，便于区分模型变化与阈值变化。",
              "- 新增字段来自1987基线官方问卷，未增加随访特征，未改标签、年龄门槛、评价人员和病例数。没有合成病例或在划分前做过采样。",
              "- 使用3×3嵌套开发集交叉验证选择特征/参数，比较强正则化、类别权重和三个固定特征组，再做折内sigmoid校准。",
              "- 所有模型的指标、分类报告、混淆矩阵、四种阈值结果和原版文件全部保留；训练不会自动替换active_model.json。", ""]
    (directory / "optimization_comparison.md").write_text("\n".join(lines), encoding="utf-8")
    write_json(directory / "optimization_comparison.json", {"validation_status": run["validation_status"],
                                                             "summary": table, "details": details})
    with (directory / "optimization_comparison.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(table[0]))
        writer.writeheader()
        writer.writerows(table)
