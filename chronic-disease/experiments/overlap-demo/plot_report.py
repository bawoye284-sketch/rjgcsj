"""Render Chinese coursework figures from saved model fitting and demonstration metrics."""

import argparse
import base64
import hashlib
from html import escape
import json
from pathlib import Path
import subprocess
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
import numpy as np
import pandas as pd
from sklearn.metrics import confusion_matrix, precision_recall_curve, roc_curve

LABELS = {"hypertension": "高血压", "diabetes": "糖尿病", "stroke": "卒中／中风",
          "heart_attack": "心脏病发作", "osteoporosis": "骨质疏松"}
COLORS = ["#376A8B", "#C87847", "#5A8F83", "#8B6BA4", "#A98B43"]
DEFAULT = .75
FIGURE_DIRECTORY = "模型性能图表"
FIGURE_FILENAMES = {
    "accuracy_comparison": "01_准确率对比",
    "balanced_comparison": "02_平衡准确率对比",
    "sample_counts": "03_样本数量统计",
    "event_distribution": "04_评估集事件分布",
    "metrics_heatmap": "05_模型性能指标热力图",
    "roc": "06_ROC曲线",
    "precision_recall": "07_PR曲线",
    "confusion_hypertension": "08_高血压混淆矩阵",
    "confusion_diabetes": "09_糖尿病混淆矩阵",
    "confusion_stroke": "10_卒中混淆矩阵",
    "confusion_heart_attack": "11_心脏病发作混淆矩阵",
    "confusion_osteoporosis": "12_骨质疏松混淆矩阵",
}


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def panel(title, xlabel="", ylabel="", curve=False, square=False):
    fig, ax = plt.subplots(figsize=(3.5, 3.8) if square else (7.2, 4.8))
    fig.subplots_adjust(left=.17 if square else .14, right=.95 if not curve else .69,
                        bottom=.26 if square else .20, top=.81)
    fig.suptitle(title, x=.5, y=.96, fontsize=12, weight="bold")
    ax.set(xlabel=xlabel, ylabel=ylabel)
    return fig, ax


def export(fig, stem, caption, qa_scripts):
    if caption:
        fig.text(.08 if fig.get_figwidth() < 4 else .14, .045, caption,
                 fontsize=8, linespacing=1.5, va="bottom", color="#56616A")
    fig.canvas.draw()
    alignment_path = stem.parent.parent / "figure_qa" / (stem.name + ".alignment.json")
    alignment_path.parent.mkdir(exist_ok=True)
    if qa_scripts:
        sys.path.insert(0, str(qa_scripts))
        from audit_panel_alignment import require_matplotlib_panel_alignment
        require_matplotlib_panel_alignment(fig, json_out=alignment_path, strict=True)
    else:
        write_json(alignment_path, {
            "status": "not_applicable", "reason": "One quantitative plot area"})
    fig.savefig(stem.with_suffix(".pdf"))
    fig.savefig(stem.with_suffix(".svg"))
    fig.savefig(stem.with_suffix(".png"), dpi=300)
    plt.close(fig)


def qa(directory, scripts):
    if scripts is None:
        return {"status": "not_run", "reason": "No QA scripts supplied; visual review remains required"}
    records, problems = [], []
    qa_dir = directory.parent / "figure_qa"
    qa_dir.mkdir(exist_ok=True)
    validator = subprocess.run([sys.executable, str(scripts / "validate_figure.py"),
                                str(Path(__file__).resolve()), "--json"], capture_output=True, text=True, encoding="utf-8")
    (qa_dir / "source_audit.json").write_text(validator.stdout, encoding="utf-8")
    if validator.returncode:
        raise RuntimeError("Figure source audit failed: " + validator.stdout[:1200])
    for pdf in sorted(directory.glob("*.pdf")):
        text = subprocess.run([sys.executable, str(scripts / "audit_pdf_text.py"), str(pdf),
                               "--min-pt", "5", "--json"], capture_output=True, text=True, encoding="utf-8")
        (qa_dir / (pdf.stem + ".text.json")).write_text(text.stdout, encoding="utf-8")
        collision = subprocess.run([sys.executable, str(scripts / "audit_figure_collisions.py"), str(pdf),
                                    "--json-out", str(qa_dir / (pdf.stem + ".collision.json"))],
                                   capture_output=True, text=True, encoding="utf-8")
        records.append({"figure": pdf.name, "text_exit": text.returncode,
                        "collision_exit": collision.returncode})
        print(f"QA {pdf.name}: text={text.returncode}, collision={collision.returncode}", flush=True)
        if text.returncode or collision.returncode:
            problems.append(f"{pdf.name}: {text.stdout[-400:]} {collision.stdout[-1800:]}")
    if problems:
        write_json(qa_dir / "summary.json", {"status": "requires_fix", "figures": records})
        raise RuntimeError("Figure QA failed: " + "\n".join(problems))
    result = {"status": "automated_checks_completed_visual_review_required", "figures": records,
              "uncertainty": "Single fixed fitted model per point; no independent generalization confidence intervals",
              "alignment": "All exported figures have a single quantitative plot area",
              "adaptation": "Project style retained; panels rebuilt from actual fitting and demonstration metrics",
              "export_contract": "Coursework: 300 dpi PNG and editable PDF/SVG; TIFF not required"}
    write_json(qa_dir / "summary.json", result)
    return result


def render(run, qa_scripts=None):
    run = Path(run)
    reports, figures = run / "reports", run / "reports" / FIGURE_DIRECTORY
    figures.mkdir(exist_ok=True)
    manifest = json.loads((run / "manifest.json").read_text(encoding="utf-8"))
    summary = pd.read_csv(reports / "metrics_summary.csv")
    predictions = pd.read_csv(reports / "predictions.csv", dtype={"participant_id": str})
    codes = list(manifest["diseases"])
    names = [LABELS[c].replace(" ", "\n") for c in codes]
    demo = summary[summary.requested_overlap == DEFAULT].set_index("disease_code").loc[codes]
    fitted = pd.DataFrame([
        {"disease_code": code, **next(r for r in manifest["diseases"][code]["experiments"]
          if r["requested_overlap"] == DEFAULT)["training_resubstitution"]}
        for code in codes]).set_index("disease_code").loc[codes]
    baseline = []
    for code in codes:
        people = predictions[(predictions.disease_code == code) & (predictions.requested_overlap == DEFAULT)]
        negatives, positives = int((people.label == 0).sum()), int(people.label.sum())
        baseline.append({"disease_code": code, "n": len(people), "events": positives,
                         "accuracy": negatives / len(people), "balanced_accuracy": .5,
                         "sensitivity": 0., "precision": 0., "f1": 0., "roc_auc": .5,
                         "average_precision": positives / len(people),
                         "tn": negatives, "fp": 0, "fn": positives, "tp": 0})
    baseline = pd.DataFrame(baseline).set_index("disease_code")
    baseline.to_csv(reports / "all_negative_baseline.csv", encoding="utf-8-sig")
    chinese_font = None
    for name in ["Microsoft YaHei", "SimHei", "Noto Sans CJK SC", "Source Han Sans SC"]:
        try:
            font_manager.findfont(font_manager.FontProperties(family=name), fallback_to_default=False)
            chinese_font = name
            break
        except ValueError:
            continue
    if chinese_font is None:
        raise RuntimeError("未找到中文字体，请安装微软雅黑、黑体或思源黑体后重新绘图")
    plt.rcParams.update({"font.family": "sans-serif", "font.sans-serif": [chinese_font, "Arial", "DejaVu Sans"],
                         "font.size": 10, "axes.labelsize": 10, "axes.titlesize": 11,
                         "xtick.labelsize": 9, "ytick.labelsize": 9, "legend.fontsize": 9,
                         "axes.spines.top": False, "axes.spines.right": False,
                         "axes.linewidth": .8, "legend.frameon": False,
                         "svg.fonttype": "none", "pdf.fonttype": 42,
                         "axes.unicode_minus": False,
                         "savefig.facecolor": "white"})
    figures_meta = []

    def save(fig, filename, title, caption):
        filename = FIGURE_FILENAMES[filename]
        export(fig, figures / filename, caption, qa_scripts)
        figures_meta.append({"file": filename, "title": title})

    positions = np.arange(len(codes))
    for metric, title, filename in [("accuracy", "准确率", "accuracy_comparison"),
                                     ("balanced_accuracy", "平衡准确率", "balanced_comparison")]:
        fig, ax = panel("五病种" + title + "对比", ylabel=title)
        for offset, label, frame, color in [(-.16, "训练集拟合", fitted, COLORS[0]),
                                           (.16, "演示集评估", demo, COLORS[1])]:
            bars = ax.bar(positions + offset, frame[metric], width=.28, label=label, color=color, zorder=3)
            for bar, value in zip(bars, frame[metric]):
                ax.text(bar.get_x() + bar.get_width()/2, value + .014, f"{value:.1%}",
                        ha="center", va="bottom", fontsize=8)
        ax.set_xticks(positions, names)
        ax.set(ylim=(0, 1.09))
        ax.yaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1))
        ax.legend(loc="upper center", bbox_to_anchor=(.5, 1.17), ncol=2, fontsize=9)
        save(fig, filename, title + "对比", "")

    fig, ax = panel("五病种样本数量", ylabel="参与者人数")
    for offset, label, values, color in [(-.16, "训练集", demo.n_train, COLORS[0]),
                                        (.16, "演示评估集", demo.n, COLORS[1])]:
        bars = ax.bar(positions + offset, values, width=.28, label=label, color=color)
        for bar, value in zip(bars, values):
            ax.text(bar.get_x() + bar.get_width()/2, value + 18, str(int(value)), ha="center", fontsize=9)
    ax.set_xticks(positions, names)
    ax.set(ylim=(0, float(demo.n_train.max()) * 1.15))
    ax.legend(loc="upper center", bbox_to_anchor=(.5, 1.17), ncol=2)
    save(fig, "sample_counts", "样本数量统计", "")

    fig, ax = panel("五病种发病事件分布", ylabel="参与者人数")
    negatives = demo.n - demo.events
    ax.bar(positions, negatives, width=.55, label="未发病", color=COLORS[0])
    ax.bar(positions, demo.events, bottom=negatives, width=.55, label="发病", color=COLORS[1])
    for position, total, events in zip(positions, demo.n, demo.events):
        ax.text(position, total + 8, f"{int(events)}/{int(total)}", ha="center", fontsize=9)
    ax.set_xticks(positions, names)
    ax.set(ylim=(0, float(demo.n.max()) * 1.15))
    ax.legend(loc="upper center", bbox_to_anchor=(.5, 1.17), ncol=2)
    save(fig, "event_distribution", "评估集事件分布", "柱顶：发病人数／总人数")

    metric_keys = ["accuracy", "balanced_accuracy", "sensitivity", "precision", "f1", "roc_auc"]
    values = demo[metric_keys].to_numpy()
    fig, ax = panel("五病种性能指标")
    ax.imshow(values, vmin=0, vmax=1, cmap="Blues", aspect="auto")
    ax.set_xticks(np.arange(6), ["准确率", "平衡\n准确率", "召回率", "精确率", "F1分数", "ROC-AUC"])
    ax.set_yticks(np.arange(5), [LABELS[c] for c in codes]);ax.tick_params(length=0)
    for row in range(5):
        for column in range(6):
            ax.text(column, row, f"{values[row,column]:.3f}", ha="center", va="center", fontsize=11,
                    color="white" if values[row,column] > .65 else "#25333D")
    save(fig, "metrics_heatmap", "六项主要指标热力图", "")

    curve_rows = []
    for kind in ["roc", "precision_recall"]:
        fig, ax = panel("五病种ROC曲线" if kind == "roc" else "五病种精确率—召回率曲线",
                        "假阳性率" if kind == "roc" else "召回率",
                        "真阳性率（召回率）" if kind == "roc" else "精确率", curve=True)
        for code, color in zip(codes, COLORS):
            p = predictions[(predictions.disease_code == code) & (predictions.requested_overlap == DEFAULT)]
            if kind == "roc":
                x, y, _ = roc_curve(p.label, p.probability)
                score = demo.loc[code, "roc_auc"]
                ax.plot(x, y, lw=1.6, color=color, label=f"{LABELS[code]} AUC={score:.3f}")
            else:
                y, x, _ = precision_recall_curve(p.label, p.probability)
                score = demo.loc[code, "average_precision"]
                ax.step(x, y, where="post", lw=1.6, color=color, label=f"{LABELS[code]} AP={score:.3f}")
            curve_rows.extend({"kind": kind, "disease_code": code, "x": float(a), "y": float(b)} for a,b in zip(x,y))
        if kind == "roc":ax.plot([0,1],[0,1], ls="--", lw=.8, color="#B6BEC4", zorder=0)
        ax.set(xlim=(-.01, 1.01), ylim=(-.01, 1.03));ax.legend(loc="upper left", bbox_to_anchor=(1.02,1), fontsize=8, borderaxespad=0)
        save(fig, kind, "ROC曲线" if kind == "roc" else "PR曲线", "")
    pd.DataFrame(curve_rows).to_csv(reports / "curve_source_data.csv", index=False)

    for code in codes:
        p = predictions[(predictions.disease_code == code) & (predictions.requested_overlap == DEFAULT)]
        matrix = confusion_matrix(p.label, p.prediction, labels=[0,1])
        disease = manifest["diseases"][code]
        stored = next(r for r in disease["experiments"] if r["requested_overlap"] == DEFAULT)
        assert matrix.tolist() == stored["evaluation"]["confusion_matrix"]
        normalized = matrix / matrix.sum(axis=1, keepdims=True)
        fig, ax = panel(LABELS[code] + "混淆矩阵", "预测结果", "实际结果", square=True)
        ax.imshow(normalized, vmin=0, vmax=1, cmap="Blues")
        ax.set_xticks([0,1],["未发病", "发病"]);ax.set_yticks([0,1],["未发病", "发病"])
        ax.tick_params(length=0)
        for row in range(2):
            for col in range(2):
                ax.text(col, row, f"{matrix[row,col]}\n({normalized[row,col]:.1%})", ha="center", va="center",
                        fontsize=11, linespacing=1.6, color="white" if normalized[row,col] > .65 else "#25333D")
        save(fig, "confusion_" + code, disease["name"] + "混淆矩阵", "单元格：人数（行占比）")
    qa_result = qa(figures, qa_scripts)

    table = []
    for code in codes:
        row = demo.loc[code]
        table.append({"评估类型": "演示集评估（非独立测试）", "疾病": manifest["diseases"][code]["name"],
                      "评估人数": int(row["n"]), "发病人数": int(row.events),
                      "准确率": f"{row.accuracy:.2%}", "平衡准确率": f"{row.balanced_accuracy:.2%}",
                      "召回率": f"{row.sensitivity:.2%}", "精确率": f"{row.precision:.2%}",
                      "特异度": f"{row.specificity:.2%}", "F1": f"{row.f1:.3f}",
                      "ROC-AUC": f"{row.roc_auc:.3f}", "平均精确率AP": f"{row.average_precision:.3f}"})
    display = pd.DataFrame(table)
    display.to_csv(reports / "presentation_summary.csv", index=False, encoding="utf-8-sig")
    explanation = ("评估类型：演示集评估（非独立测试）。五个病种分别使用二分类模型，分类阈值固定为0.5。"
                   "全部指标由保存的逐人预测计算；实验设置与划分记录保存在实验说明及运行清单中。")
    notes = ["# 五病种模型演示性能汇报", "", explanation, "", "| 疾病 | 准确率 | 平衡准确率 | 召回率 | 精确率 | F1 | ROC-AUC |", "|---|---:|---:|---:|---:|---:|---:|"]
    notes += [f"| {r['疾病']} | {r['准确率']} | {r['平衡准确率']} | {r['召回率']} | {r['精确率']} | {r['F1']} | {r['ROC-AUC']} |" for r in table]
    notes += ["", "以上为演示集上的指标，不作为独立测试或新患者预测能力的结论。",
              "", "中文图表统一位于模型性能图表文件夹，包含ROC、PR和五病种混淆矩阵。",
              "", "数据、权重与代码独立保存；网站继续使用其原有模型版本。"]
    (reports / "presentation_notes.md").write_text("\n".join(notes) + "\n", encoding="utf-8")
    images = []
    for item in figures_meta:
        encoded = base64.b64encode((figures / (item["file"] + ".png")).read_bytes()).decode()
        downloads = ' · '.join(f'<a href="{FIGURE_DIRECTORY}/{item["file"]}.{ext}">{label}</a>'
                               for ext, label in [("png", "PNG图片"), ("pdf", "PDF文件"), ("svg", "SVG矢量图")])
        images.append(f'<section><h2>{escape(item["title"])}</h2><p>{downloads}</p><img alt="{escape(item["title"])}" src="data:image/png;base64,{encoded}"/></section>')
    html = '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>五病种模型演示性能报告</title>'
    html += '<style>body{margin:0;background:#f3f6f8;color:#263c47;font:15px/1.8 system-ui,"Microsoft YaHei",sans-serif}main{max-width:1200px;margin:auto;padding:40px 22px}h1{font-size:30px}h2{font-size:21px}.notice{padding:20px;background:#fff0df;border-left:5px solid #c87847}section{margin:26px 0;padding:24px;background:white;border-radius:10px}img{display:block;max-width:100%;margin:auto}table{border-collapse:collapse;white-space:nowrap;font-size:13px}th,td{padding:10px 12px;border-bottom:1px solid #e0e7eb;text-align:right}th{background:#eaf0f4}.scroll{overflow:auto}.muted{color:#627680}</style><main>'
    html += '<h1>五病种模型演示性能报告</h1><div class="notice">'+escape(explanation)+'</div>'
    html += '<p class="muted">ExtraTrees · 固定阈值0.5 · 固定原评估人群 · 五个独立二分类模型 · 未接替在线模型</p>'
    html += '<section><h2>演示集性能指标（非独立测试）</h2><div class="scroll">'+display.to_html(index=False, border=0)+'</div><p>汇总表集中展示各病种性能指标。模型对比、基线及实验过程见下方图表和原始运行记录。</p></section>'
    html += ''.join(images) + '<section><h2>如何核查</h2><p>metrics_summary.csv保存全部重叠比例的指标；predictions.csv逐人保存标签、概率和是否参加训练；split_manifest.csv保存训练与评估成员；每病种JSON包含完整分类报告及混淆矩阵。</p><p>每点只有一次固定模型拟合，曲线不代表重复实验均值；未提供独立泛化含义的置信区间。模型权重、原始数据与既有报告保留。</p></section></main></html>'
    (reports / "performance_report.html").write_text(html, encoding="utf-8")
    write_json(reports / "figure_index.json", {"figure_directory": FIGURE_DIRECTORY, "figures": figures_meta, "qa": qa_result})
    manifest["plot_source_sha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    write_json(run / "manifest.json", manifest)
    print(display.to_string(index=False), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--qa-scripts", type=Path)
    args = parser.parse_args()
    render(args.run, args.qa_scripts)
