"""Reconstruct conservative, survivor-conditional NHEFS disease-report cohorts."""

import argparse
from collections import Counter
from datetime import date

from common import (DATA, PROCESSED, REPORTS, ROOT, TARGET_CONFIG, assigned_split,
                    enough_profile_information, read_json, sha256, targets, write_json, write_jsonl)
from prepare_nhefs_disease_inventory import read_layout

LABEL_CONFIG = ROOT / "config" / "nhefs_label_rules.json"
RULES = read_json(LABEL_CONFIG)["rules"]
FEATURE_NAMES = ["age", "sex_male", "weight_kg", "self_rated_health",
                 "smoking_current", "drinking_any", "exercise_regular"]
FEATURE_NAMES += [f"history_{code}" for code in RULES]
FEATURE_NAMES += ["high_cholesterol_history", "health_worsened", "weight_gain_10lb",
                  "weight_loss_10lb", "hip_fracture_history"]


def integer(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def yes_no(value):
    return {1: 1, 2: 0}.get(integer(value))


def load_source(raw_name, layout_name, id_name, keys):
    manifest = {entry["file"]: entry for entry in read_json(DATA / "download_manifest.json")["files"]}
    raw = DATA / "raw" / raw_name
    layout_path = DATA / "documentation" / layout_name
    for path, relative in [(raw, f"raw/{raw_name}"), (layout_path, f"documentation/{layout_name}")]:
        if sha256(path) != manifest[relative]["sha256"]:
            raise ValueError(f"Source checksum mismatch: {relative}")
    layout = read_layout(layout_path)
    missing = set(keys) - layout.keys()
    if missing:
        raise ValueError(f"Unknown source fields: {missing}")
    selected = set(keys) | {id_name}
    result = {}
    for line in raw.read_bytes().splitlines():
        row = {}
        for key in selected:
            field = layout[key]
            start = field["start_1_based"] - 1
            row[key] = line[start:start + field["width"]].decode("ascii").strip()
        person_id = row.pop(id_name)
        if len(person_id) != 5 or not person_id.isdigit() or person_id in result:
            raise ValueError(f"Invalid/duplicate participant ID in {raw_name}")
        result[person_id] = row
    return result


def disease_state(row, code, wave):
    rule = RULES[code]
    current = integer(row.get(rule["current"]))
    prior_name = rule[f"prior{wave}"]
    prior = integer(row.get(prior_name)) if prior_name else None
    confirmation = [integer(row.get(name)) for name in rule["confirm"]]
    if 96 in [current, *confirmation] or (current == 2 and (prior == 1 or 1 in confirmation)):
        return None, "conflicting"
    if current == 95:
        return None, "borderline"
    if current == 1 or prior == 1:
        return 1, "reported"
    if current == 2:
        return 0, "not_reported"
    return None, "unknown_or_skipped"


def survey_date(row, wave):
    values = [integer(row.get(f"INTV{wave}{suffix}")) for suffix in ["YR", "MO", "DY"]]
    year, month, day = values
    if year is None or not 80 <= year <= 94 or month is None or not 1 <= month <= 12:
        return None
    # Use month precision consistently; dates are not precise incident timestamps.
    return date(1900 + year, month, 15)


def make_features(row, age, sex):
    pounds = integer(row.get("WEIGHNOW"))
    health = integer(row.get("GENERAL"))
    smoker = yes_no(row.get("SMOKENOW"))
    if smoker is None:
        smoker = yes_no(row.get("CSMKNOW"))
    # A previous never-smoker still needs the current 100-cigarette screening answer.
    if smoker is None and yes_no(row.get("SMOKE100")) == 0:
        smoker = 0
    features = {"age": age, "sex_male": {1: 1, 2: 0}.get(sex),
                "weight_kg": round(pounds * 0.45359237, 3) if pounds and 55 <= pounds <= 700 else None,
                "self_rated_health": health if health in {1, 2, 3, 4, 5} else None,
                "smoking_current": smoker, "drinking_any": yes_no(row.get("DRINKYR")),
                "exercise_regular": yes_no(row.get("EXERPROG"))}
    features.update({f"history_{code}": disease_state(row, code, "87")[0] for code in RULES})
    health_change, weight_change = integer(row.get("HEALTH")), integer(row.get("WHTCOMPR"))
    hip_prior, hip_current = integer(row.get("BROKENHI")), integer(row.get("BRKNHIP"))
    # Prior yes leads to a skip of BRKNHIP. Missing/skipped answers are never no.
    hip_history = 1 if hip_prior == 1 or hip_current == 1 else 0 if hip_current == 2 else None
    features.update({
        "high_cholesterol_history": yes_no(row.get("HIGHBLCH")),
        "health_worsened": int(health_change in {3, 4}) if health_change in {1, 2, 3, 4} else None,
        "weight_gain_10lb": int(weight_change == 1) if weight_change in {1, 2, 3} else None,
        "weight_loss_10lb": int(weight_change == 2) if weight_change in {1, 2, 3} else None,
        "hip_fracture_history": hip_history})
    return features


def run_audit(min_age=65):
    keys87 = {"GENERAL", "WEIGHNOW", "SMOKENOW", "CSMKNOW", "SMOKE100", "DRINKYR", "EXERPROG",
              "HIGHBLCH", "HEALTH", "WHTCOMPR", "BROKENHI", "BRKNHIP"}
    keys92 = set()
    for rule in RULES.values():
        keys87.update([rule["current"], rule["year"], *rule["confirm"]])
        if rule["prior87"]:
            keys87.add(rule["prior87"])
        keys92.update([rule["current"], rule["year"], rule["prior92"]])
        # HBPMEDIC was replaced in 1992. Only use fields present in both layouts.
        keys92.update(name for name in rule["confirm"] if name != "HBPMEDIC")
    wave87 = load_source("n87int.txt", "intv87.inputs.labels.txt", "SEQNO", keys87)
    wave92 = load_source("N92int.txt", "intv92.inputs.labels.txt", "HANESEQ", keys92)
    vital_keys = {"AGEINT87", "SEXSUBJ", "VTLST87", "VTLST92", "INTDIS87", "INTDIS92",
                  "QSTYPE87", "QSTYPE92", "INTV87YR", "INTV87MO", "INTV87DY",
                  "INTV92YR", "INTV92MO", "INTV92DY"}
    vital = load_source("n92vitl.txt", "vitl.inputs.labels.txt", "SEQNUM", vital_keys)
    audit_rows, cohort_rows, summary = [], [], {}
    for target in targets():
        code = target["code"]
        counts, months = Counter(), []
        for person_id, trace in vital.items():
            age = integer(trace["AGEINT87"])
            start, end = survey_date(trace, "87"), survey_date(trace, "92")
            before, after = wave87.get(person_id), wave92.get(person_id)
            reason, label = None, None
            baseline = disease_state(before, code, "87")[0] if before else None
            if person_id not in wave87 or integer(trace["VTLST87"]) != 1:
                reason = "no_alive_baseline_interview"
            elif age is None or not min_age <= age <= 98:
                reason = "age_outside_scope_or_unknown"
            elif start is None:
                reason = "baseline_date_unknown"
            elif baseline == 1:
                reason = "already_diagnosed"
            elif baseline is None:
                reason = "baseline_" + disease_state(before, code, "87")[1]
            elif integer(trace["VTLST92"]) == 3:
                reason = "deceased_before_followup_excluded_not_negative"
            elif integer(trace["VTLST92"]) != 1 or after is None:
                reason = "lost_or_no_alive_followup_interview"
            elif end is None or not 36 <= (end - start).days / 30.4375 <= 84:
                reason = "followup_date_unknown_or_outside_3_to_7_years"
            elif integer(after.get(RULES[code]["prior92"])) == 1:
                reason = "followup_historical_report_conflicts_with_baseline"
            else:
                final, final_state = disease_state(after, code, "92")
                year = integer(after.get(RULES[code]["year"]))
                if final == 0:
                    label, reason = 0, "eligible_negative"
                elif final == 1 and year is not None and 0 <= year <= 94:
                    if start.year < 1900 + year <= end.year:
                        label, reason = 1, "eligible_positive"
                    else:
                        reason = "reported_year_not_strictly_after_baseline"
                elif final == 1:
                    reason = "positive_report_year_unknown"
                else:
                    reason = "followup_" + final_state
            features = make_features(before, age, integer(trace["SEXSUBJ"])) if label is not None else None
            if label is not None and not enough_profile_information(features):
                label, reason = None, "insufficient_baseline_profile_information"
            counts[reason] += 1
            record = {"participant_id": person_id, "disease_code": code, "reason": reason,
                      "label": label, "baseline_state": baseline, "age": age,
                      "baseline_date": start.isoformat() if start else None,
                      "followup_date": end.isoformat() if end else None,
                      "split": assigned_split(person_id)}
            audit_rows.append(record)
            if label is not None:
                elapsed = round((end - start).days / 30.4375, 2)
                months.append(elapsed)
                record = {**record, "followup_months": elapsed,
                          "features": features}
                cohort_rows.append(record)
        relevant = [row for row in cohort_rows if row["disease_code"] == code]
        summary[code] = {"name": target["name_zh"], "counts": dict(counts),
                         "eligible": len(relevant), "events": sum(row["label"] for row in relevant),
                         "followup_months_min": min(months) if months else None,
                         "followup_months_max": max(months) if months else None,
                         "splits": {split: {"n": sum(r["split"] == split for r in relevant),
                                             "events": sum(r["label"] for r in relevant if r["split"] == split)}
                                    for split in ["development", "evaluation"]},
                         "feature_missing_counts": {name: sum(row["features"][name] is None for row in relevant)
                                                    for name in FEATURE_NAMES}}
    write_jsonl(PROCESSED / "cohorts.jsonl", cohort_rows)
    write_jsonl(PROCESSED / "cohort_exclusions.jsonl", audit_rows)
    report = {"task": "survivor_conditional_new_disease_report", "baseline": "1987",
              "followup": "1992", "min_age": min_age, "config_sha256": sha256(TARGET_CONFIG),
              "label_rules_sha256": sha256(LABEL_CONFIG),
              "feature_schema_sha256": sha256(ROOT / "config" / "nhefs_feature_schema.json"),
              "rules_sha256": sha256(__file__), "seed": 20261015,
              "notes": ["Historical US cohort; not externally validated in Chinese community elders.",
                        "Only alive followup interview completers; deceased are excluded, never coded negative.",
                        "Require known sex and at least two of five health/lifestyle inputs, identical to online eligibility.",
                        "Positive diagnosis year must be strictly after baseline calendar year; same-year ambiguous events excluded.",
                        "Month precision survey dates use the 15th; this is a survey-interval report task, not exact five-year incidence."],
              "source_files": {name: sha256(DATA / "raw" / name) for name in ["n87int.txt", "N92int.txt", "n92vitl.txt"]},
              "cohorts_sha256": sha256(PROCESSED / "cohorts.jsonl"), "diseases": summary}
    write_json(REPORTS / "nhefs" / "cohort_audit.json", report)
    lines = ["# 五病种队列审计", "", "基线1987、随访1992，起点65岁及以上；仅限两端存活且有可用访谈的选择人群。", "",
             "死亡、失访、未知、矛盾和同起点年份的模糊事件不编码为阴性。阳性要求首次报告年份严格晚于起点年份。", "",
             "| 病种 | 有效人数 | 新报告病例 | 开发集/病例 | 留出评价集/病例 | 随访月数范围 |",
             "| --- | ---: | ---: | --- | --- | --- |"]
    for code, item in summary.items():
        dev, hold = item["splits"]["development"], item["splits"]["evaluation"]
        lines.append(f"| {item['name']} | {item['eligible']} | {item['events']} | {dev['n']}/{dev['events']} | {hold['n']}/{hold['events']} | {item['followup_months_min']}–{item['followup_months_max']} |")
    lines += ["", "各病种排除流向及输入缺失数见 cohort_audit.json；个人级审核记录保存在被Git忽略的processed/目录。", "",
              "原始代码：1是、2否；-1不适用、-7拒答、-8未知、-9未查明；95临界状态不当作无病，96矛盾单列。", "",
              "历史预填为有病时，未重复询问的-1不当作无病。年龄使用追踪表AGEINT87，不能使用截至1986年底年龄的AGENOW。", "",
              "该原型估计完成随访生存者的新报告风险，存在选择偏倚，不能直接解释为中国老人精确五年发病率。", ""]
    (REPORTS / "nhefs" / "cohort_audit.md").write_text("\n".join(lines), encoding="utf-8")
    print({code: (item["eligible"], item["events"], item["splits"]) for code, item in summary.items()})
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--min-age", type=int, default=65)
    run_audit(parser.parse_args().min_age)
