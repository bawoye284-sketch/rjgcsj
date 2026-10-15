"""Inventory and extract NHEFS disease fields without inventing training labels.

Only the Python standard library is needed. Values remain source-code strings;
blank, skip, unknown and historical prefill values are not recoded to 0/1.
"""

import argparse
from collections import Counter
from datetime import datetime, timezone
from fnmatch import fnmatchcase
import hashlib
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "config" / "nhefs_disease_targets.json"
WAVES = {
    "1982_1984": ("N82int.txt", "intv82.inputs.labels.txt", "SEQNUM1", 4356),
    "1986": ("n86int.txt", "intv86.inputs.labels.txt", "SEQNO", 5364),
    "1987": ("n87int.txt", "intv87.inputs.labels.txt", "SEQNO", 5190),
    "1992": ("N92int.txt", "intv92.inputs.labels.txt", "HANESEQ", 8074),
}
TIERS = {
    "priority": "优先审计",
    "extension": "扩展候选",
    "limited": "时间或定义受限",
    "historical": "早期病史／待补随访",
}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n",
                         encoding="utf-8")
    temporary.replace(path)


def read_layout(path):
    source = path.read_text(encoding="utf-8-sig")
    labels = {name.upper(): label.replace("''", "'") for name, label in re.findall(
        r"^\s*(\w+)\s*=\s*'((?:''|[^'])*)'", source, re.MULTILINE)}
    fields = {}
    for start, name, width in re.findall(r"@(\d+)\s+(\w+)\s+\$CHAR(\d+)\.", source):
        key = name.upper()
        if key in fields:
            raise ValueError(f"Duplicate layout field: {path.name}: {name}")
        fields[key] = {"name": name, "start_1_based": int(start), "width": int(width),
                       "label": labels.get(key, "")}
    if not fields:
        raise ValueError(f"No SAS character fields found: {path}")
    return fields


def selected_fields(target, wave, layout):
    selectors = target["fields_82" if wave == "1982_1984" else "fields_later"]
    return [name for name in layout if any(
        fnmatchcase(name, selector.upper()) for selector in selectors)]


def context_fields(layout):
    # Keep original names and codes. These are not yet validated online features.
    return [key for key, field in layout.items() if key in {
        "SUBAGE", "SUBSEX", "AGENOW", "AGECF", "NAGE", "AGE87", "AGE82", "AGE92",
        "TRACSTAT", "IFVITAL", "LINTSTAT", "PREVINT", "LINTDATE", "LNDOB", "LSSEX",
        "FINTMO", "FINTDAY", "FINTYR", "RSLTMO", "RSLTDAY", "RSLTYR", "PRIMRESP",
        "RELPROX", "REASPROX", "PRXMHQD", "CANRESP1", "CANRESP2", "GENERAL",
    } or re.search(r"INTERVIEW.*(DATE|MONTH|YEAR)|AGE OF SUBJECT|SUBJECT.*AGE",
                   field["label"], re.IGNORECASE)]


def extract_wave(data_dir, wave, specification, layout, keys, manifest_files):
    raw_name, layout_name, id_key, length = specification
    raw_path = data_dir / "raw" / raw_name
    output = data_dir / "derived" / f"disease_fields_{wave}.jsonl"
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(".jsonl.part")
    digest = hashlib.sha256()
    ids = set()
    counts = {key: Counter() for key in keys}
    records = 0
    id_field = layout[id_key]
    expected = manifest_files[f"raw/{raw_name}"]
    try:
        with raw_path.open("rb") as source, temporary.open("w", encoding="utf-8") as sink:
            for line_number, raw_line in enumerate(source, 1):
                digest.update(raw_line)
                line = raw_line.rstrip(b"\r\n")
                if len(line) != length:
                    raise ValueError(f"{raw_name}:{line_number}: unexpected record length {len(line)}")
                start = id_field["start_1_based"] - 1
                participant_id = line[start:start + id_field["width"]].decode("ascii").strip()
                if not re.fullmatch(r"\d{5}", participant_id) or participant_id in ids:
                    raise ValueError(f"{raw_name}:{line_number}: invalid/duplicate ID")
                ids.add(participant_id)
                values = {}
                for key in keys:
                    field = layout[key]
                    start = field["start_1_based"] - 1
                    value = line[start:start + field["width"]].decode("ascii").strip()
                    values[key] = value
                    counts[key][value] += 1
                sink.write(json.dumps({"participant_id": participant_id, "wave": wave,
                                       "raw_values": values}, ensure_ascii=False) + "\n")
                records += 1
        if digest.hexdigest() != expected["sha256"]:
            raise ValueError(f"{raw_name}: SHA-256 differs from download manifest")
        temporary.replace(output)
    finally:
        if temporary.exists():
            temporary.unlink()
    return {
        "raw_file": f"raw/{raw_name}", "source_url": expected["source_url"],
        "sha256": digest.hexdigest(), "layout_file": f"documentation/{layout_name}",
        "layout_source_url": manifest_files[f"documentation/{layout_name}"]["source_url"],
        "layout_sha256": hashlib.sha256((data_dir / "documentation" / layout_name).read_bytes()).hexdigest(),
        "derived_file": f"derived/{output.name}", "derived_sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "records": records, "selected_fields": len(keys),
        "nonblank_counts": {key: records - values[""] for key, values in counts.items()},
        "raw_code_counts": {key: dict(sorted(values.items())) for key, values in counts.items()},
    }, ids


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, default=ROOT / "data" / "nhefs")
    parser.add_argument("--report-dir", type=Path, default=ROOT / "reports" / "nhefs")
    args = parser.parse_args()
    data_dir = args.data_dir.resolve()
    report = args.report_dir.resolve()
    config = json.loads(CONFIG.read_text(encoding="utf-8"))
    targets = config["targets"]
    codes = [target["code"] for target in targets]
    if len(codes) != len(set(codes)):
        raise ValueError("Target codes must be unique")
    reviewed = set(config.get("reviewed_training_target_codes", []))
    if not reviewed.issubset(config["first_version_target_codes"]):
        raise ValueError("Only selected targets may be marked codebook-reviewed")
    first_version_codes = config["first_version_target_codes"]
    if (len(first_version_codes) != 5 or len(set(first_version_codes)) != 5
            or not set(first_version_codes).issubset(codes)):
        raise ValueError("The first version must select exactly five registered targets")
    if config["first_version_task"] != "multilabel_incident_disease_report":
        raise ValueError("Expected a multilabel future disease-report task")
    manifest = json.loads((data_dir / "download_manifest.json").read_text(encoding="utf-8"))
    manifest_files = {item["file"]: item for item in manifest["files"]}
    layouts = {}
    for wave, (_, layout_name, _, length) in WAVES.items():
        path = data_dir / "documentation" / layout_name
        if hashlib.sha256(path.read_bytes()).hexdigest() != manifest_files[f"documentation/{layout_name}"]["sha256"]:
            raise ValueError(f"Layout checksum mismatch: {layout_name}")
        layouts[wave] = read_layout(path)
        if max(field["start_1_based"] - 1 + field["width"]
               for field in layouts[wave].values()) != length:
            raise ValueError(f"Unexpected layout extent: {layout_name}")
    catalogue = []
    for target in targets:
        fields = {wave: selected_fields(target, wave, layout) for wave, layout in layouts.items()}
        if not any(fields.values()):
            raise ValueError(f"No source fields found for target {target['code']}")
        catalogue.append({"code": target["code"], "name_zh": target["name_zh"],
                          "kind": target["kind"], "tier": target["tier"], "notes": target["notes"],
                          "selected_for_first_version": target["code"] in first_version_codes,
                          "training_enabled": config["training_enabled"] and target["code"] in reviewed,
                          "label_review_status": "codebook_checked_research_definition" if target["code"] in reviewed else "not_reviewed",
                          "fields_by_wave": fields,
                          "derived_files": {wave: f"derived/disease_fields_{wave}.jsonl"
                                            for wave, names in fields.items() if names}})
    statistics = {}
    ids_by_wave = {}
    dictionary = {}
    for wave, specification in WAVES.items():
        layout = layouts[wave]
        keys = set(context_fields(layout))
        for item in catalogue:
            keys.update(item["fields_by_wave"][wave])
        keys = sorted(keys, key=lambda key: layout[key]["start_1_based"])
        statistics[wave], ids_by_wave[wave] = extract_wave(
            data_dir, wave, specification, layout, keys, manifest_files)
        dictionary[wave] = {key: layout[key] for key in keys}
        print(f"{wave}: {statistics[wave]['records']} records, {len(keys)} fields", flush=True)
    for item in catalogue:
        item["nonblank_by_wave"] = {
            wave: {key: statistics[wave]["nonblank_counts"][key] for key in keys}
            for wave, keys in item["fields_by_wave"].items()}
    overlap = len(ids_by_wave["1987"] & ids_by_wave["1992"])
    write_json(report / "catalog.json", {"dataset": "NHEFS", "training_enabled": config["training_enabled"],
                                         "first_version_task": config["first_version_task"],
                                         "first_version_target_codes": first_version_codes,
                                         "targets": catalogue})
    write_json(report / "field_dictionary.json", dictionary)
    write_json(data_dir / "derived" / "extraction_statistics.json", {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "configuration_sha256": hashlib.sha256(CONFIG.read_bytes()).hexdigest(),
        "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "label_review_status": "see_per_target_catalog", "training_enabled": config["training_enabled"],
        "first_version_target_codes": first_version_codes,
        "matched_1987_1992_ids_all_ages": overlap, "waves": statistics})
    lines = ["**NHEFS 多疾病原始字段盘点**", "",
             "由 `chronic-disease/src/prepare_nhefs_disease_inventory.py` 从已下载官方文件生成。", "",
             f"登记 {len(catalogue)} 个疾病、历史健康报告或事件方向，不是 {len(catalogue)} 个已可训练模型。",
             "第一版仅选择5个候选病种：高血压、糖尿病、卒中、心脏病发作、骨质疏松。其余37个方向作为资料备查，不纳入第一版目标。",
             "任务为5病种多标签预测，每病单独建队列并预测；一个人可对应多个结果，概率不要求相加为1。最终是否训练取决于标签和事件审计。",
             "已核对编码的研究任务见各条目training_enabled与label_review_status；开启训练不代表临床验证通过。队列与性能见cohort_audit及training报告。", "",
             "| 访谈轮次 | 实际提取人数（全年龄） | 提取字段数 |", "| --- | ---: | ---: |"]
    for wave, result in statistics.items():
        lines.append(f"| {wave} | {result['records']:,} | {result['selected_fields']} |")
    lines.extend(["", f"1987 与 1992 共同出现的 ID 为 {overlap:,} 个；这是全年龄访谈交集，不是老年训练样本量或有效随访队列。", "",
                  "| 方向 | 类型 | 审查优先级 | 1982—84字段数 | 1986字段数 | 1987字段数 | 1992字段数 |",
                  "| --- | --- | --- | ---: | ---: | ---: | ---: |"])
    for item in catalogue:
        availability = " | ".join(str(len(item["fields_by_wave"][wave])) for wave in WAVES)
        lines.append(f"| {item['name_zh']} | {item['kind']} | {TIERS[item['tier']]} | {availability} |")
    lines.extend(["", "字段数只表示资料存在；历史预填、年份、用药、住院与当轮疾病问题不可视为等价标签。非空也不表示有效回答或患病。", "",
                  "`catalog.json` 列出逐病种字段、每轮非空数及注意事项；`field_dictionary.json` 提供官方变量名、原始英文说明、位置和宽度。", "",
                  "`data/nhefs/derived/disease_fields_*.jsonl`（相对于 chronic-disease/）按轮保存全部候选方向对应的个人级原始值；按 ID 跨轮关联，不跨轮拼成独立样本。", "",
                  "`data/nhefs/derived/extraction_statistics.json` 保存完整原始编码分布、输入输出摘要；它和个人级派生数据均不进入 Git。", "",
                  "本字段提取步骤保留原始值；正式研究标签由audit_cohorts.py按已核对规则生成，不把死亡、失访、空白或拒答当作阴性。", "",
                  "优先审查65岁及以上的1987→1992队列；逐病种确认时间范围。跌倒等回顾性12个月问题不得套用五年窗口。", "",
                  "早期单轮病史可作为候选历史信息；若没有可比后续结局，不能用于该病的未来发病训练。", "",
                  "来源：[CDC/NCHS NHEFS](https://wwwn.cdc.gov/nchs/nhanes/nhefs/default.aspx)。数据为历史美国人群，尚未完成对中国社区老人的独立评价。", ""])
    report.mkdir(parents=True, exist_ok=True)
    (report / "README.md").write_text("\n".join(lines), encoding="utf-8")
    print(json.dumps({"registered_targets": len(catalogue),
                      "first_version_target_codes": first_version_codes,
                      "tier_counts": dict(Counter(item["tier"] for item in catalogue)),
                      "matched_1987_1992_ids_all_ages": overlap,
                      "training_enabled": config["training_enabled"]}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
