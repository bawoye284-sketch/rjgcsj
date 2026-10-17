"""Chinese figures from actual training/test membership and unchanged model weights."""

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

COLORS = ["#376A8B", "#C87847", "#5A8F83", "#8B6BA4", "#A98B43"]
FIGURE_DIRECTORY = "\u6a21\u578b\u6027\u80fd\u56fe\u8868"


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def panel(title, xlabel="", ylabel="", curve=False, square=False):
    fig, ax = plt.subplots(figsize=(3.5, 3.8) if square else (7.2, 4.8))
    fig.subplots_adjust(left=.17 if square else .14, right=.95 if not curve else .69,
                        bottom=.32 if square else .25, top=.81)
    fig.suptitle(title, x=.5, y=.96, fontsize=12, weight="bold")
    ax.set(xlabel=xlabel, ylabel=ylabel)
    return fig, ax


def export(fig, stem, caption, qa_scripts):
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
              "uncertainty": "Fixed existing weights; internal cohort evaluation; participant-bootstrap intervals saved separately",
              "alignment": "All exported figures have a single quantitative plot area",
              "adaptation": "Style-only inheritance from project plotting helpers",
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
    names = [manifest["diseases"][c]["name"].split("（")[0].replace("／中风", "") for c in codes]
    frames = {split: summary[summary.split == split].set_index("disease_code").loc[codes]
              for split in ["train", "test"]}
    test, train = frames["test"], frames["train"]
    chosen = None
    for name in ["Microsoft YaHei", "SimHei", "Noto Sans CJK SC", "Source Han Sans SC"]:
        try:
            font_manager.findfont(font_manager.FontProperties(family=name), fallback_to_default=False)
            chosen = name
            break
        except ValueError:
            continue
    if chosen is None:
        raise RuntimeError("请安装微软雅黑、黑体或思源黑体后重新绘图")
    plt.rcParams.update({"font.family": "sans-serif", "font.sans-serif": [chosen, "Arial", "DejaVu Sans"],
                         "font.size": 10, "axes.labelsize": 10, "axes.titlesize": 11,
                         "xtick.labelsize": 9, "ytick.labelsize": 9, "legend.fontsize": 9,
                         "axes.spines.top": False, "axes.spines.right": False,
                         "axes.linewidth": .8, "legend.frameon": False,
                         "svg.fonttype": "none", "pdf.fonttype": 42, "axes.unicode_minus": False,
                         "savefig.facecolor": "white"})
    index = []

    def save(fig, filename, title, caption):
        export(fig, figures / filename, caption, qa_scripts)
        index.append({"file": filename, "title": title})

    positions = np.arange(len(codes))
    for key, number, title in [("accuracy", "01", "准确率"), ("balanced_accuracy", "02", "平衡准确率")]:
        fig, ax = panel("训练集与测试集" + title, ylabel=title)
        for offset, frame, label, color in [(-.16, train, "训练集拟合", COLORS[0]),
                                            (.16, test, "测试集评估", COLORS[1])]:
            bars = ax.bar(positions + offset, frame[key], width=.28, label=label, color=color)
            for bar, value in zip(bars, frame[key]):
                ax.text(bar.get_x() + bar.get_width()/2, value+.014, f"{value:.1%}", ha="center", fontsize=8)
        ax.set_xticks(positions, names)
        ax.set(ylim=(0, 1.09))
        ax.yaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1))
        ax.legend(loc="upper center", bbox_to_anchor=(.5, 1.17), ncol=2)
        save(fig, number + "_训练集与测试集" + title, "训练集与测试集" + title,
             "保留现有模型权重与阈值；按实际拟合成员分别计算训练集和测试集指标。\n测试集成员未参加对应模型拟合；本结果属于现有队列内部评估。")

    fig, ax = panel("五病种训练集与测试集规模", ylabel="参与者人数")
    for offset, frame, label, color in [(-.16, train, "训练集", COLORS[0]), (.16, test, "测试集", COLORS[1])]:
        bars = ax.bar(positions + offset, frame.n, width=.28, label=label, color=color)
        for bar, n in zip(bars, frame.n):
            ax.text(bar.get_x()+bar.get_width()/2, n+18, str(int(n)), ha="center", fontsize=9)
    ax.set_xticks(positions, names)
    ax.set(ylim=(0, float(train.n.max())*1.15))
    ax.legend(loc="upper center", bbox_to_anchor=(.5, 1.17), ncol=2)
    save(fig, "03_训练集与测试集规模", "训练集与测试集规模",
         "每个病种分别核对训练集与测试集参与者，二者互斥且覆盖该病种全部原始队列。\n五个模型独立拟合；同一参与者可属于多个病种队列，人数不能跨病种简单相加。")

    fig, ax = panel("测试集发病事件分布", ylabel="参与者人数")
    negatives = test.n - test.events
    ax.bar(positions, negatives, width=.55, label="未发病", color=COLORS[0])
    ax.bar(positions, test.events, bottom=negatives, width=.55, label="发病", color=COLORS[1])
    for position, n, events in zip(positions, test.n, test.events):
        ax.text(position, n+2, f"{int(events)}/{int(n)}", ha="center", fontsize=9)
    ax.set_xticks(positions, names)
    ax.set(ylim=(0, float(test.n.max())*1.15))
    ax.legend(loc="upper center", bbox_to_anchor=(.5, 1.17), ncol=2)
    save(fig, "04_测试集事件分布", "测试集事件分布",
         "柱顶标记为发病人数／测试人数；少量阳性事件会使性能估计不稳定。\n未发病者较多，应同时报告召回率、平衡准确率与全阴性基线。")

    keys = ["accuracy", "balanced_accuracy", "sensitivity", "precision", "f1", "roc_auc"]
    values = test[keys].to_numpy()
    fig, ax = panel("五病种测试集性能指标")
    ax.imshow(values, vmin=0, vmax=1, cmap="Blues", aspect="auto")
    ax.set_xticks(range(6), ["准确率", "平衡\n准确率", "召回率", "精确率", "F1分数", "ROC-AUC"])
    ax.set_yticks(range(5), names)
    ax.tick_params(length=0)
    for row in range(5):
        for col in range(6):
            ax.text(col, row, f"{values[row,col]:.3f}", ha="center", va="center", fontsize=11,
                    color="white" if values[row,col] > .65 else "#25333D")
    save(fig, "05_测试集性能指标热力图", "测试集性能指标热力图",
         "指标由测试集逐人预测计算；沿用原模型阈值0.5，不更改权重。\n无预测阳性时，精确率与F1按0计；测试集发病事件较少。")

    curves = []
    for kind, filename, title in [("roc", "06_测试集ROC曲线", "测试集ROC曲线"),
                                   ("pr", "07_测试集PR曲线", "测试集精确率—召回率曲线")]:
        fig, ax = panel(title, "假阳性率" if kind == "roc" else "召回率",
                        "真阳性率（召回率）" if kind == "roc" else "精确率", curve=True)
        for code, color, name in zip(codes, COLORS, names):
            p = predictions[(predictions.disease_code == code) & (predictions.split == "test")]
            if kind == "roc":
                x, y, _ = roc_curve(p.label, p.probability)
                score = test.loc[code, "roc_auc"]
                ax.plot(x, y, lw=1.6, color=color, label=f"{name} ({score:.3f})")
            else:
                y, x, _ = precision_recall_curve(p.label, p.probability)
                score = test.loc[code, "average_precision"]
                ax.step(x, y, where="post", lw=1.6, color=color, label=f"{name} ({score:.3f})")
            curves.extend({"curve": kind, "disease_code": code, "x": float(a), "y": float(b)} for a,b in zip(x,y))
        if kind == "roc":
            ax.plot([0,1], [0,1], ls="--", lw=.8, color="#B6BEC4", zorder=0)
        ax.set(xlim=(-.01, 1.01), ylim=(-.01, 1.03))
        ax.legend(loc="upper left", bbox_to_anchor=(1.02,1), borderaxespad=0, fontsize=8)
        save(fig, filename, title,
             "图例括号为ROC-AUC；曲线仅使用各病种实际测试集，灰色虚线为随机排序参考。\n结果为点估计；每病种仅有少量发病事件，区间另存于指标JSON。" if kind == "roc" else
             "图例括号为平均精确率（AP）；曲线仅使用各病种实际测试集。\n结果为点估计；需结合测试集事件率及阳性人数解读。")
    pd.DataFrame(curves).to_csv(reports / "curve_source_data.csv", index=False)

    for number, code, name in zip(range(8,13), codes, names):
        p = predictions[(predictions.disease_code == code) & (predictions.split == "test")]
        matrix = confusion_matrix(p.label, p.prediction, labels=[0,1])
        assert matrix.tolist() == manifest["diseases"][code]["metrics"]["test"]["confusion_matrix"]
        normalized = matrix / matrix.sum(axis=1, keepdims=True)
        fig, ax = panel(name + "混淆矩阵\n测试集", "预测结果", "实际结果", square=True)
        ax.imshow(normalized, vmin=0, vmax=1, cmap="Blues")
        ax.set_xticks([0,1], ["未发病", "发病"])
        ax.set_yticks([0,1], ["未发病", "发病"])
        ax.tick_params(length=0)
        for row in range(2):
            for col in range(2):
                ax.text(col, row, f"{matrix[row,col]}\n({normalized[row,col]:.1%})", ha="center", va="center",
                        fontsize=11, linespacing=1.6, color="white" if normalized[row,col] > .65 else "#25333D")
        save(fig, f"{number:02d}_" + name + "测试集混淆矩阵", name + "测试集混淆矩阵",
             f"测试人数：{len(p)}；发病数：{int(p.label.sum())}；阈值：0.5。\n单元格为人数及所在行百分比。\n现有模型权重保持不变。")

    qa_result = qa(figures, qa_scripts)
    table, baseline_rows = [], []
    for code, name in zip(codes, names):
        row = test.loc[code]
        baseline = manifest["diseases"][code]["constant_baseline"]
        baseline_rows.append({"disease_code": code, **{k: v for k, v in baseline.items() if isinstance(v, (int,float))}})
        table.append({"疾病": name, "训练人数": int(train.loc[code,"n"]), "测试人数": int(row.n),
                      "测试发病人数": int(row.events), "准确率": f"{row.accuracy:.2%}",
                      "全阴性基线准确率": f"{baseline['accuracy']:.2%}", "平衡准确率": f"{row.balanced_accuracy:.2%}",
                      "召回率": f"{row.sensitivity:.2%}", "精确率": f"{row.precision:.2%}",
                      "F1": f"{row.f1:.3f}", "ROC-AUC": f"{row.roc_auc:.3f}"})
    pd.DataFrame(baseline_rows).to_csv(reports / "constant_baseline.csv", index=False, encoding="utf-8-sig")
    display = pd.DataFrame(table)
    display.to_csv(reports / "presentation_summary.csv", index=False, encoding="utf-8-sig")
    explanation = ("使用现有模型权重，按实际拟合成员区分训练集与测试集。训练集用于展示拟合表现；"
                   "测试集只包含未参加对应模型拟合的记录，指标由保存的逐人预测重新计算。"
                   "本报告属于此前已查看队列的内部评估，不代表全新外部验证。")
    interpretation = ("每病种测试集只有2至4例发病，性能估计不稳定。当前0.5阈值下五病种测试召回率均为0；"
                      "较高准确率主要与未发病者占多数有关，应与全阴性基线、AUC、召回率和混淆矩阵一起解读。")
    notes = ["# 五病种训练集与测试集性能汇报", "", explanation, "", "模型权重与阈值保持不变；历史过程记录保留。", "",
             "| 疾病 | 训练人数 | 测试人数 | 测试准确率 | 平衡准确率 | 召回率 | F1 | ROC-AUC |",
             "|---|---:|---:|---:|---:|---:|---:|---:|"]
    notes += [f"| {r['疾病']} | {r['训练人数']} | {r['测试人数']} | {r['准确率']} | {r['平衡准确率']} | {r['召回率']} | {r['F1']} | {r['ROC-AUC']} |" for r in table]
    notes += ["", interpretation, "", "完整指标与500次参与者bootstrap区间见各病种JSON；中文图表统一放在模型性能图表文件夹。"]
    (reports / "presentation_notes.md").write_text("\n".join(notes) + "\n", encoding="utf-8")
    images = []
    for item in index:
        encoded = base64.b64encode((figures / (item["file"] + ".png")).read_bytes()).decode()
        links = " · ".join(f'<a href="{FIGURE_DIRECTORY}/{item["file"]}.{ext}">{ext.upper()}</a>' for ext in ["png","pdf","svg"])
        images.append(f'<section><h2>{escape(item["title"])}</h2><img alt="{escape(item["title"])}" src="data:image/png;base64,{encoded}"><p>{links}</p></section>')
    html = ('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
            '<title>五病种训练与测试性能报告</title><style>body{font-family:"Microsoft YaHei",sans-serif;max-width:1200px;margin:36px auto;padding:0 24px;line-height:1.8;color:#25333d}'
            'table{border-collapse:collapse;white-space:nowrap;font-size:14px}th,td{padding:10px;border:1px solid #dce3e8;text-align:right}th,.note{background:#edf3f6}.note{padding:18px}'
            '.table{overflow-x:auto}section{border-top:1px solid #dce3e8;margin-top:28px;padding-top:18px}img{max-width:100%;height:auto;max-height:720px}a{color:#376a8b}</style>'
            f'<h1>五病种训练集与测试集性能报告</h1><p>版本：{escape(manifest["version"])}</p><p>{explanation}</p><p class="note">{interpretation}</p>'
            f'<div class="table">{display.to_html(index=False)}</div><p>权重未修改，未重新训练。完整分类报告、MCC、Brier分数、对数损失及95%区间见各病种JSON。</p>'
            + "".join(images) + '</html>')
    (reports / "performance_report.html").write_text(html, encoding="utf-8")
    write_json(reports / "figure_index.json", {"figure_directory": FIGURE_DIRECTORY, "font": chosen, "figures": index, "qa": qa_result})
    manifest["plot_source_sha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    write_json(run / "manifest.json", manifest)
    print(display.to_string(index=False), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--qa-scripts", type=Path)
    args = parser.parse_args()
    render(args.run, args.qa_scripts)
