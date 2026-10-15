"""Export traceable single-panel performance figures and a standalone report.

Figure contract: compare discrimination, operating errors and probability
calibration on the same held-out people. All figures use real saved predictions;
no smoothing or simulated observations. Individual figures have one plot area.
"""

import base64
import csv
from html import escape
import importlib.util
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from sklearn.calibration import calibration_curve
from sklearn.metrics import precision_recall_curve, roc_curve

from common import sha256, write_json

LABELS = {"hypertension": "Hypertension", "diabetes": "Diabetes", "stroke": "Stroke",
          "heart_attack": "Heart attack", "osteoporosis": "Osteoporosis"}
COLORS = {"LogisticRegression": "#286B8B", "XGBoost": "#BD6B39"}
METRICS = ["roc_auc", "average_precision", "accuracy", "balanced_accuracy", "sensitivity",
           "specificity", "precision", "negative_predictive_value", "f1", "mcc", "brier", "log_loss"]


def style():
    plt.rcParams.update({"font.family": "sans-serif", "font.sans-serif": ["Arial", "DejaVu Sans"],
                         "font.size": 10, "axes.titlesize": 12, "axes.labelsize": 10,
                         "xtick.labelsize": 9, "ytick.labelsize": 9, "legend.fontsize": 9,
                         "axes.spines.top": False, "axes.spines.right": False,
                         "axes.linewidth": .8, "svg.fonttype": "none", "pdf.fonttype": 42,
                         "savefig.facecolor": "white"})


def panel(title, xlabel, ylabel):
    figure, axis = plt.subplots(figsize=(7.2, 4.5))
    figure.subplots_adjust(left=.14, right=.96, bottom=.20, top=.81)
    axis.set(title=title, xlabel=xlabel, ylabel=ylabel)
    return figure, axis


def export(figure, path, caption, qa_scripts=None):
    figure.text(.14, .035, caption, fontsize=8, va="bottom")
    figure.canvas.draw()
    if qa_scripts:
        specification = importlib.util.spec_from_file_location(
            "panel_alignment_audit", Path(qa_scripts) / "audit_panel_alignment.py")
        module = importlib.util.module_from_spec(specification)
        import sys
        sys.modules[specification.name] = module
        specification.loader.exec_module(module)
        module.require_matplotlib_panel_alignment(figure, json_out=path.with_suffix(".alignment.json"), strict=True)
    else:
        write_json(path.with_suffix(".alignment.json"), {
            "status": "not_applicable", "reason": "Single quantitative plot area; no comparable panel grid"})
    figure.savefig(path.with_suffix(".pdf"))
    figure.savefig(path.with_suffix(".svg"))
    figure.savefig(path.with_suffix(".png"), dpi=300)
    figure.savefig(path.with_suffix(".tiff"), dpi=600, pil_kwargs={"compression": "tiff_lzw"})
    plt.close(figure)


def figure_set(code, disease, rows, destination, qa_scripts):
    destination.mkdir(parents=True, exist_ok=True)
    selected = disease["selected_model"]
    series = {}
    for family in disease["models"]:
        matching = [row for row in rows if row["disease_code"] == code
                    and row["model"] == family and row["split"] == "evaluation"]
        series[family] = (np.asarray([row["label"] for row in matching]),
                          np.asarray([row["probability"] for row in matching]))
    title = LABELS[code]
    y, p = series[selected]
    n, events = len(y), int(y.sum())
    footnote = f"Held-out participants: n={n}, events={events}. Historical US survivor-completer cohort."
    curves = {}
    for kind in ["roc", "precision_recall", "calibration"]:
        names = {"roc": ("False positive rate", "Sensitivity"),
                 "precision_recall": ("Recall", "Precision"),
                 "calibration": ("Mean predicted probability", "Observed event proportion")}
        fig, ax = panel(f"{title}: {kind.replace('_', ' ')}", *names[kind])
        curves[kind] = {}
        calibration_extent = .05
        for family, (actual, probability) in series.items():
            if kind == "roc":
                x, z, _ = roc_curve(actual, probability)
            elif kind == "precision_recall":
                z, x, _ = precision_recall_curve(actual, probability)
            else:
                z, x = calibration_curve(actual, probability, n_bins=5, strategy="quantile")
            if kind == "calibration":
                edges = np.quantile(probability, np.linspace(0, 1, 6))
                count = np.bincount(np.searchsorted(edges[1:-1], probability), minlength=6)
                count = count[count > 0]
                assert len(count) == len(z)
                denominator = 1 + 1.96 ** 2 / count
                center = (z + 1.96 ** 2 / (2 * count)) / denominator
                half = 1.96 * np.sqrt(z * (1 - z) / count + 1.96 ** 2 / (4 * count ** 2)) / denominator
                lower, upper = np.minimum(z, center - half), np.maximum(z, center + half)
                ax.errorbar(x, z, yerr=[z - lower, upper - z], color=COLORS[family],
                            linewidth=1.4, fmt="o-", markersize=4, capsize=3, label=family)
                calibration_extent = max(calibration_extent, float(max(x.max(), upper.max())))
            elif kind == "precision_recall":
                ax.step(x, z, where="post", color=COLORS[family], linewidth=1.8, label=family)
            else:
                ax.plot(x, z, color=COLORS[family], linewidth=1.8, label=family)
            curves[kind][family] = {"x": x.tolist(), "y": z.tolist()}
            if kind == "calibration":
                curves[kind][family].update(bin_n=count.tolist(), wilson95_lower=lower.tolist(), wilson95_upper=upper.tolist())
        if kind == "precision_recall":
            ax.axhline(y.mean(), linestyle="--", color="#777777", linewidth=1, label="Event-rate baseline")
        else:
            ax.plot([0, 1], [0, 1], linestyle="--", color="#999999", linewidth=1, label="Reference")
        if kind == "calibration":
            limit = min(1., calibration_extent * 1.15)
            ax.set(xlim=(0, limit), ylim=(0, limit))
        else:
            ax.set(xlim=(0, 1), ylim=(0, 1.04))
        handles, labels = ax.get_legend_handles_labels()
        fig.legend(handles, labels, loc="upper center", bbox_to_anchor=(.55, .98), ncol=3, frameon=False)
        caption = footnote + ("\n95% Wilson intervals for observed bin rates; axes zoom include all estimates." if kind == "calibration" else "")
        export(fig, destination / kind, caption, qa_scripts)
    chosen = disease["models"][selected]
    for family, model_info in disease["models"].items():
        matrix = np.asarray(model_info["evaluation"]["confusion_matrix"])
        fig, ax = panel(f"{title}: confusion matrix", "Predicted class", "Observed class")
        ax.imshow(matrix, cmap="Blues", vmin=0, vmax=max(int(matrix.max()), 1), aspect="auto")
        ax.set(xticks=[0, 1], yticks=[0, 1], xticklabels=["Negative", "Positive"], yticklabels=["Negative", "Positive"])
        for (i, j), value in np.ndenumerate(matrix):
            ax.text(j, i, f"{[['TN', 'FP'], ['FN', 'TP']][i][j]}\n{value}", ha="center", va="center",
                    fontsize=13, color="white" if value > matrix.max() * .55 else "#202020")
        filename = "confusion_matrix" if family == selected else f"confusion_matrix_{family}"
        export(fig, destination / filename, f"{family}; development-selected threshold={model_info['threshold']:.4f}.\n" + footnote, qa_scripts)
    fig, ax = panel(f"{title}: development threshold selection", "Probability threshold", "Development out-of-fold rate")
    development = [r for r in rows if r["disease_code"] == code and r["model"] == selected
                   and r["split"] == "development_oof"]
    dy, dp = np.asarray([r["label"] for r in development]), np.asarray([r["probability"] for r in development])
    precision, recall, thresholds = precision_recall_curve(dy, dp)
    ax.plot(thresholds, precision[:-1], label="Precision", color="#286B8B")
    ax.plot(thresholds, recall[:-1], label="Sensitivity", color="#BD6B39")
    ax.axvline(chosen["threshold"], color="#666666", linestyle="--", label="Chosen threshold")
    ax.set(ylim=(0, 1.04))
    handles, labels = ax.get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper center", bbox_to_anchor=(.55, .98), ncol=3, frameon=False)
    export(fig, destination / "threshold_selection", "Development OOF only; evaluation participants did not determine the threshold.", qa_scripts)
    write_json(destination / "curve_source_data.json", curves)


def embedded(path):
    return "data:image/png;base64," + base64.b64encode(path.read_bytes()).decode("ascii")


def write_presentation_notes(run, directory):
    exploratory = bool(run.get("optimization"))
    status = "优化实验版本（重复使用已查看的留出集，只作探索性比较）" if exploratory else "正式版本"
    lines = ["# 模型性能汇报说明", "", f"{status}：{run['version']}。如实记录真实留出结果，不将低性能包装成有效临床预测。", "",
             "本次完成：官方字段核对、老人疾病队列、逻辑回归与XGBoost比较、开发集交叉校准、阈值选择、独立评价及独立服务。", "",
             "| 病种 | 所选模型 | 开发人数/病例 | 留出人数/病例 | ROC-AUC（95%区间） | AP | 敏感度 | 特异度 | 精确率 | F1 | 准确率 |",
             "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for code, disease in run["diseases"].items():
        if disease["status"] != "trained":
            continue
        model = disease["models"][disease["selected_model"]]
        metric = model["evaluation"]
        lower, upper = metric["confidence_intervals"]["intervals"]["roc_auc"]
        lines.append(f"| {disease['name']} | {disease['selected_model']} | {disease['n_development']}/{disease['events_development']} | {metric['n']}/{metric['events']} | {metric['roc_auc']:.3f}（{lower:.3f}–{upper:.3f}） | {metric['average_precision']:.3f} | {metric['sensitivity']:.3f} | {metric['specificity']:.3f} | {metric['precision']:.3f} | {metric['f1']:.3f} | {metric['accuracy']:.3f} |")
    lines += ["", "## 汇报时需要解释", "",
              "- 当前模型区分能力偏弱，区间较宽，尤其少事件病种；不足以支持实际风险决策。",
              "- 阈值来自开发集折外预测，开发目标为敏感度至少0.70，但不保证留出集达到。",
              "- 类别不平衡时全部判阴性也可能有很高准确率，应同时展示事件数、AP、召回率、精确率和混淆矩阵。",
              "- AP是average precision，不是梯形积分PR-AUC；指标名保持一致。",
              "- 95%区间为固定模型下500次参与者重抽样的百分位区间，不包括重新开发模型的不确定性。",
              "- 模型和阈值选择不使用最终留出集，同一人员的多轮记录不跨开发/评价集合。",
              "- 人群是历史美国65岁以上完成随访生存者；标签是问卷新报告，不是精确五年发病率。",
              "- 阳性首次报告年份严格晚于起点年；同年模糊事件、死亡、失访、未知和矛盾不当作阴性。",
              "- 主要输入为年龄、性别、自报体重、自评健康、生活习惯和其他病史，缺乏丰富的同期体检/化验可能限制性能。", "",
              "## 文件使用", "",
              "performance_report.html用于离线阅读，metrics_summary.csv用于汇报表格；每病metrics.json保留完整指标和区间。figures/中PNG适合PPT，PDF/SVG为矢量图，TIFF为600dpi。", "",
              "后续应核对标签与适用人群、补充预测起点前真实可用的体检和化验、扩大有效事件并在目标社区独立评价。不能根据当前留出结果反复挑参数后仍称为独立验证。", ""]
    (directory / "presentation_notes.md").write_text("\n".join(lines), encoding="utf-8")
    if exploratory:
        path = directory / "presentation_notes.md"
        content = path.read_text(encoding="utf-8")
        content = content.replace("开发目标为敏感度至少0.70，但不保证留出集达到。",
                                  "默认在开发集特异度至少0.80的范围内最大化Youden指数，不保证留出集达到；同时保留平衡、70%召回和F1策略。")
        content = content.replace("模型和阈值选择不使用最终留出集，同一人员的多轮记录不跨开发/评价集合。",
                                  "本轮参数和特征搜索只使用开发集，外层验证折同时排除搜索和校准。原留出集此前已查看，因此比较仍属于探索性分析，需要新数据验证。")
        content = content.replace("开发集交叉校准、阈值选择、独立评价及独立服务。",
                                  "开发集嵌套选择和校准、阈值比较、原留出集探索性评价及独立服务代码。")
        content = content.replace("缺乏丰富的同期体检/化验可能限制性能。",
                                  "本轮增加高胆固醇病史、近期健康/体重变化和髋部骨折史；仍缺乏同期体检/化验。")
        content += "\n优化细节、各病种改善与下降见 optimization_comparison.md；四种阈值的误报/漏报取舍见 operating_points.csv。默认阈值预先规定，不按留出准确率选择。\n"
        path.write_text(content, encoding="utf-8")


def render_report(run, rows, directory, qa_scripts=None):
    style()
    summary_rows, sections, operating_rows = [], [], []
    for code, disease in run["diseases"].items():
        if disease["status"] != "trained":
            sections.append(f"<h2>{escape(disease['name'])}</h2><p>未训练：{escape(disease['reason'])}</p>")
            continue
        figure_dir = directory / code / "figures"
        figure_set(code, disease, rows, figure_dir, qa_scripts)
        selected = disease["selected_model"]
        details = [f"<h2>{escape(disease['name'])}</h2>",
                   f"<p>开发集 {disease['n_development']} 人 / {disease['events_development']} 例；"
                   f"{'原留出比较集' if run.get('optimization') else '独立留出集'} {disease['n_evaluation']} 人 / {disease['events_evaluation']} 例。"
                   f"模型依据开发集选择：<b>{selected}</b>；留出集未用于选择。</p>",
                   "<table><tr><th>指标</th><th>LogisticRegression</th><th>XGBoost</th></tr>"]
        for metric in METRICS:
            values = [disease["models"][name]["evaluation"][metric] for name in COLORS]
            details.append(f"<tr><td>{metric}</td>" + "".join(f"<td>{v:.4f}</td>" if v is not None else "<td>不可估计</td>" for v in values) + "</tr>")
        details.append("</table>")
        if "constant_baseline" in disease:
            baseline = disease["constant_baseline"]
            details.append(f"<p>基准：使用开发集事件率作常数概率，阈值0.5时全部判阴性。留出集准确率{baseline['accuracy']:.4f}，敏感度{baseline['sensitivity']:.4f}，AP {baseline['average_precision']:.4f}。高准确率可能主要来自阴性占多数。</p>")
        for family, info in disease["models"].items():
            metric = info["evaluation"]
            for policy, point in info.get("operating_points", {}).items():
                operating_rows.append({"disease_code": code, "disease_name": disease["name"], "model": family,
                                       "policy": policy, "default": policy == info["threshold_policy"],
                                       "selected_using_development": family == selected,
                                       "threshold": point["threshold"], **{key: point[key] for key in METRICS},
                                       "tn": point["confusion_matrix"][0][0], "fp": point["confusion_matrix"][0][1],
                                       "fn": point["confusion_matrix"][1][0], "tp": point["confusion_matrix"][1][1]})
            summary_rows.append({"disease_code": code, "disease_name": disease["name"], "model": family,
                                 "selected_using_development": family == selected,
                                 "n_development": disease["n_development"], "events_development": disease["events_development"],
                                 "n_evaluation": metric["n"], "events_evaluation": metric["events"],
                                 "threshold": metric["threshold"], **{key: metric[key] for key in METRICS},
                                 "tn": metric["confusion_matrix"][0][0], "fp": metric["confusion_matrix"][0][1],
                                 "fn": metric["confusion_matrix"][1][0], "tp": metric["confusion_matrix"][1][1],
                                 **{f"{key}_ci95_{bound}": values[index]
                                    for key, values in metric["confidence_intervals"]["intervals"].items()
                                    for index, bound in enumerate(["lower", "upper"])}})
        intervals = disease["models"][selected]["evaluation"]["confidence_intervals"]
        details.append("<h3>所选模型的95%区间</h3><p>固定模型、参与者重抽样的百分位区间；未包含完整模型开发过程的不确定性。</p><table><tr><th>指标</th><th>下界</th><th>上界</th></tr>")
        for key, (lower, upper) in intervals["intervals"].items():
            details.append(f"<tr><td>{key}</td><td>{lower:.4f}</td><td>{upper:.4f}</td></tr>")
        details.append("</table>")
        if disease["models"][selected].get("operating_points"):
            details.append("<h3>所选模型的阈值取舍（开发集预先确定）</h3><table><tr><th>策略</th><th>阈值</th><th>准确率</th><th>召回率</th><th>特异度</th><th>精确率</th><th>误报FP</th><th>漏报FN</th></tr>")
            for policy, point in disease["models"][selected]["operating_points"].items():
                details.append(f"<tr><td>{policy}</td><td>{point['threshold']:.5f}</td><td>{point['accuracy']:.3f}</td><td>{point['sensitivity']:.3f}</td><td>{point['specificity']:.3f}</td><td>{point['precision']:.3f}</td><td>{point['confusion_matrix'][0][1]}</td><td>{point['confusion_matrix'][1][0]}</td></tr>")
            details.append("</table><p>默认specificity80：仅在开发集特异度≥0.80的阈值中最大化Youden指数。screening70保留原召回目标；阈值改变不会改变AUC/AP。各策略尚无临床效用验证。</p>")
        for name in ["confusion_matrix", "roc", "precision_recall", "calibration", "threshold_selection"]:
            details.append(f'<figure><img alt="{name}" src="{embedded(figure_dir / (name + ".png"))}"></figure>')
        other_model = next(name for name in disease["models"] if name != selected)
        other_image = figure_dir / f"confusion_matrix_{other_model}.png"
        details.append(f'<figure><img alt="Comparison model confusion matrix" src="{embedded(other_image)}"></figure>')
        sections.append("\n".join(details))
    write_json(directory / "metrics_summary.json", summary_rows)
    write_json(directory / "figure_provenance.json", {
        "plot_source_sha256": sha256(__file__), "backend": "matplotlib",
        "source_data": "saved real held-out predictions; threshold plot uses development OOF",
        "uncertainty": "scalar metrics: participant-bootstrap 95% intervals in metrics.json; curves are single-fit estimates, not fold means",
        "layout": "one quantitative axis per figure; alignment gate not applicable",
        "formats": {"pdf": "editable vector", "svg": "editable vector", "png": "300 dpi preview", "tiff": "600 dpi LZW"}})
    if summary_rows:
        with (directory / "metrics_summary.csv").open("w", encoding="utf-8-sig", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(summary_rows[0]))
            writer.writeheader()
            writer.writerows(summary_rows)
    if operating_rows:
        with (directory / "operating_points.csv").open("w", encoding="utf-8-sig", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(operating_rows[0]))
            writer.writeheader()
            writer.writerows(operating_rows)
    html = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>五病种模型性能报告</title>
<style>body{max-width:1100px;margin:40px auto;padding:0 24px;font:16px/1.7 system-ui;color:#243444;background:#fff}table{border-collapse:collapse;width:100%;margin:20px 0}td,th{border:1px solid #d6dce3;padding:8px;text-align:left}th{background:#edf3f7}img{max-width:100%}figure{margin:28px 0}.note{background:#fff3da;padding:18px}h2{margin-top:60px}</style>
<h1>五病种模型性能报告</h1>"""
    html += f"<p>训练版本：{escape(run['version'])}；随机种子：{run['seed']}。</p>"
    if run.get("optimization"):
        html += "<div class='note'><strong>优化实验：原留出集此前已查看，本报告属于探索性前后比较，不能称为新的独立验证。</strong>本轮只在开发集搜索特征和参数，并使用包含搜索与校准的嵌套交叉验证。默认阈值优先限制误报；同时公开四种阈值策略的结果。<a href='optimization_comparison.md'>查看与原版对比</a></div>"
    html += "<div class='note'>真实NHEFS历史美国人群，起点65岁及以上，仅限完成有效随访的生存者。目标为调查间隔内新报告，不是精确五年发病率，也未在中国社区老人中验证。糖尿病等少事件任务的区间可能很宽。</div>"
    html += "<p>每人固定分入开发或独立评价集合。开发集用于搜索、折内预处理、校准和阈值选择；图中ROC、PR、校准与混淆矩阵均来自留出集，阈值图来自开发集。AP为average precision；校准图采用5个等频分箱。</p>"
    html += "<p>CSV与JSON汇总、逐模型完整指标及PDF/SVG图表与本报告同目录保存；个人级预测另存于本地processed目录。</p>"
    html += "\n".join(sections) + "</html>"
    (directory / "performance_report.html").write_text(html, encoding="utf-8")
    write_presentation_notes(run, directory)
    (directory / "README.md").write_text(
        "# 模型性能交付\n\n打开 performance_report.html 查看自包含报告。metrics_summary.csv 可直接用于汇报表格；metrics_summary.json 保留数值。\n\n"
        "每个病种的 metrics.json 保留两种模型的指标、分类报告、混淆矩阵及95%重抽样区间；figures/ 包含PNG、PDF、SVG和曲线源数据。\n\n"
        "run.json 保留开发集模型选择依据、参数搜索、阈值、数据摘要、代码摘要和环境版本。个人级预测保存在 data/nhefs/processed/runs/<版本>/predictions.jsonl，不进入Git。\n\n"
        "这是历史美国完成随访生存者的研究原型结果；少事件任务应同时展示区间和事件数，不直接解释为中国老人五年发病概率。\n", encoding="utf-8")
    if run.get("optimization"):
        from compare_models import write_comparison
        write_comparison(run, rows, directory)
        (directory / "README.md").write_text(
            "# 优化实验性能交付\n\n本轮重复使用已查看的原留出集，属于探索性比较，不能称为新的独立验证。\n\n"
            "performance_report.html：完整图表；optimization_comparison.md/csv/json：与原版配对对比；"
            "operating_points.csv：四种开发集阈值的误报/漏报取舍。\n\n"
            "metrics_summary.csv/json和各病metrics.json保留完整性能指标、分类报告、混淆矩阵和95%重抽样区间。"
            "figures/保留PNG/PDF/SVG/TIFF及曲线源数据；run.json记录嵌套开发集搜索与参数。\n\n"
            "权重和个人级预测/折划分审计在独立models/与processed/运行目录；原版本完整保留，默认不更换活动模型。\n",
            encoding="utf-8")
