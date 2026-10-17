"""Add an explicitly simulated, time-distributed personal-risk demonstration timeline."""

import argparse
from collections import Counter
from datetime import datetime, timedelta
import hashlib
import json
import math
import os
from pathlib import Path
import random
import re
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
import uuid

from import_nhefs_users import CHRONIC, ROOT, Database, connection, digest, json_text, literal
from populate_sample_assessments import write_json

FIELDS = {"systolicPressure": "systolic_pressure", "diastolicPressure": "diastolic_pressure",
          "heartRate": "heart_rate", "spo2": "spo2", "temperature": "temperature", "respiratoryRate": "respiratory_rate"}
NOTE = "本条为模拟生理测量及模型预测记录，用于展示近30天趋势。"


def model_call(url, payload=None):
    request = Request(url, data=None if payload is None else json_text(payload).encode("utf-8"),
                      headers={"Content-Type": "application/json"}, method="GET" if payload is None else "POST")
    with urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def run(args):
    if not re.fullmatch(r"[a-zA-Z0-9_]{1,40}", args.account):
        raise ValueError("Account must be a simple local account name")
    if not 5 <= args.count <= 90 or not 2 <= args.days <= 30:
        raise ValueError("Use 5-90 records spread over 2-30 days")
    model_url = args.model_url.rstrip("/")
    endpoint = urlsplit(model_url)
    if endpoint.scheme != "http" or endpoint.hostname not in {"localhost", "127.0.0.1"}:
        raise ValueError("Only a local model service is supported")
    db = Database(args.mysql, connection(args))
    users = db.objects("SELECT JSON_OBJECT('id',id,'account',user_account,'role',user_role) FROM `user` WHERE user_account=" + literal(args.account) + ";")
    if len(users) != 1 or users[0]["role"] != 2:
        raise ValueError("A unique normal user account is required")
    user_id = int(users[0]["id"])
    profiles = db.objects("SELECT JSON_OBJECT('age',age,'sexMale',sex_male,'bmi',bmi) FROM elder_risk_profile WHERE user_id=" + str(user_id) + ";")
    if len(profiles) != 1 or any(value is None for value in profiles[0].values()):
        raise ValueError("Existing age, sex and BMI profile is required")
    profile = profiles[0]
    health = model_call(model_url + "/health")
    if not health.get("ok") or not health.get("model_ready"):
        raise ValueError("Personalized model is not ready")
    version = health["model_version"]
    batch = f"timeline-{args.account}-{args.count}-{args.days}-v1"
    output = CHRONIC / "reports/database-import" / batch
    existing = db.objects("SELECT JSON_OBJECT('id',id,'measured_at',DATE_FORMAT(measured_at,'%Y-%m-%d %H:%i:%s')," +
                          ",".join("'" + field + "'," + field for field in FIELDS.values()) +
                          ") FROM elder_vital_measurement WHERE user_id=" + str(user_id) + " ORDER BY measured_at,id;")
    print(json_text({"account": args.account, "user_id": user_id, "planned_records": args.count, "days": args.days,
                     "existing_measurements": len(existing), "model_version": version, "apply": args.apply}), flush=True)
    if not args.apply:
        return
    output.mkdir(parents=True, exist_ok=True)
    plan_file = output / "plan.json"
    if plan_file.exists():
        plan = json.loads(plan_file.read_text(encoding="utf-8"))
        if plan["user_id"] != user_id or plan["profile"] != profile or plan["model_version"] != version:
            raise ValueError("Saved timeline plan differs from current user/profile/model")
    else:
        now = datetime.fromisoformat(db.query("SELECT DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i:%s');")) - timedelta(seconds=5)
        rng = random.Random(int(hashlib.sha256(batch.encode()).hexdigest()[:16], 16))
        items = []
        for i in range(args.count):
            fraction = i / (args.count - 1)
            measured = now - timedelta(days=(args.days - .5) * (1 - fraction))
            time_string = measured.replace(microsecond=0).strftime("%Y-%m-%d %H:%M:%S")
            wave = math.sin(fraction * math.pi * 4)
            # Smooth variation and a short middle-period rise make software trends visible.
            excursion = math.exp(-((fraction - .62) / .09) ** 2)
            morning = 1 if i % 2 else -1
            values = {"systolicPressure": round(124 + 8*wave + 19*excursion + morning*2 + rng.gauss(0,2.2),1),
                      "diastolicPressure": round(76 + 4*wave + 6*excursion + rng.gauss(0,1.8),1),
                      "heartRate": round(73 + 6*wave + 17*excursion + rng.gauss(0,2),1),
                      "spo2": round(max(91,min(100,97.4 - 2.7*excursion + rng.gauss(0,.45))),1),
                      "temperature": round(36.5 + .15*wave + .9*excursion + rng.gauss(0,.12),1),
                      "respiratoryRate": round(16 + 1.2*wave + 5*excursion + rng.gauss(0,.6),1)}
            request_id = str(uuid.uuid5(uuid.NAMESPACE_URL, batch + ":" + str(user_id) + ":" + str(i)))
            items.append({"index": i, "measured_at": time_string, "measurement": values, "request_id": request_id})
        plan = {"batch": batch, "account": args.account, "user_id": user_id, "profile": profile,
                "model_version": version, "data_kind": "simulated_timeline", "note": NOTE,
                "items": items, "existing_measurement_ids": [row["id"] for row in existing]}
        write_json(plan_file, plan)
    request_ids = ",".join(literal(item["request_id"]) for item in plan["items"])
    already = int(db.query("SELECT COUNT(*) FROM personalized_risk_assessment WHERE user_id=" + str(user_id) + " AND request_id IN (" + request_ids + ");"))
    if already:
        if already != args.count:
            raise ValueError("Partial existing timeline requires review before resuming")
        print("Timeline already exists; no duplicate records inserted.", flush=True)
        return
    # All model calls finish before one short transaction writes measurements and predictions.
    records = []
    for item in plan["items"]:
        measured = datetime.fromisoformat(item["measured_at"])
        current = {"measured_at": item["measured_at"], **{wire: item["measurement"][key] for key, wire in FIELDS.items()}}
        previous = [{key: value for key, value in row.items() if key != "id"}
                    for row in existing if measured - timedelta(days=90) <= datetime.fromisoformat(row["measured_at"]) < measured]
        previous += [{"measured_at": old["measured_at"], **{wire: old["measurement"][key] for key, wire in FIELDS.items()}}
                     for old in plan["items"] if old["index"] < item["index"]]
        previous.sort(key=lambda row: row["measured_at"])
        payload = {"user_id": user_id, "profile": {"age": profile["age"], "sex_male": profile["sexMale"], "bmi": profile["bmi"]},
                   "current": current, "history": previous}
        response = model_call(model_url + "/predict", payload)
        if response.get("model_version") != version or not 0 <= response["group_risk_probability"] <= 1:
            raise ValueError("Unexpected model version or invalid score")
        if NOTE not in response["reasons"]:
            response["reasons"].append(NOTE)
        snapshot = {"profile": profile, "measurement": item["measurement"], "measuredAt": item["measured_at"],
                    "history": previous, "dataOrigin": {"type": "simulated_timeline", "batch": batch, "note": NOTE,
                                                            "simulated_fields": list(FIELDS)}}
        records.append({**item, "input_snapshot": snapshot, "response": response})
        if len(records) % 15 == 0:
            print(f"Model predictions {len(records)}/{args.count}", flush=True)
    write_json(output / "predictions.json", records)
    statements = ["SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION';",
                  "CREATE TEMPORARY TABLE timeline_guard(ok TINYINT NOT NULL CHECK(ok=1));",
                  "INSERT INTO timeline_guard VALUES(IF(GET_LOCK(" + literal(batch) + ",10)=1,1,0));", "START TRANSACTION;",
                  "INSERT INTO timeline_guard SELECT IF(COUNT(*)=0,1,0) FROM personalized_risk_assessment WHERE user_id=" + str(user_id) + " AND request_id IN (" + request_ids + ");"]
    for item in records:
        response = item["response"]
        values = [user_id] + [item["measurement"][key] for key in FIELDS] + [item["measured_at"]]
        statements.append("INSERT INTO elder_vital_measurement (user_id," + ",".join(FIELDS.values()) + ",measured_at) VALUES (" + ",".join(map(literal,values)) + ");")
        values = [user_id, response["group_risk_probability"], response["personal_change_score"],
                  int(response["baseline_available"]), response["risk_level"], json_text(response["reasons"]),
                  json_text(response["single_alerts"]), int(response["news2_complete"]), response["assessment_basis"],
                  response["model_name"], response["model_version"], item["request_id"], json_text(item["input_snapshot"]), item["measured_at"]]
        statements.append("INSERT INTO personalized_risk_assessment (user_id,group_risk_probability,personal_change_score,baseline_available,risk_level,reasons_json,single_alerts_json,news2_complete,assessment_basis,model_name,model_version,request_id,input_snapshot_json,assessment_time) VALUES (" + ",".join(map(literal,values)) + ");")
    statements += ["COMMIT;", "DO RELEASE_LOCK(" + literal(batch) + ");"]
    db.query("\n".join(statements))
    stored = db.objects("SELECT JSON_OBJECT('id',id,'request_id',request_id,'time',DATE_FORMAT(assessment_time,'%Y-%m-%d %H:%i:%s'),'score',group_risk_probability,'change',personal_change_score,'baseline',baseline_available,'risk',risk_level) FROM personalized_risk_assessment WHERE user_id=" + str(user_id) + " AND request_id IN (" + request_ids + ") ORDER BY assessment_time;")
    if len(stored) != args.count:
        raise RuntimeError("Stored timeline count differs")
    summary = {"status": "completed", "batch": batch, "account": args.account, "user_id": user_id,
               "added_predictions": len(stored), "added_measurements": len(stored), "model_version": version,
               "first_time": stored[0]["time"], "last_time": stored[-1]["time"],
               "baseline_available_records": sum(row["baseline"] for row in stored),
               "risk_levels": dict(Counter(row["risk"] for row in stored)), "data_kind": "simulated_timeline", "note": NOTE,
               "existing_measurements_preserved": True, "existing_assessments_preserved": True,
               "weights_modified": False, "code_sha256": digest(Path(__file__)), "records": stored}
    write_json(output / "summary.json", summary)
    print(json_text({key: value for key, value in summary.items() if key != "records"}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--account", default="lty")
    parser.add_argument("--count", type=int, default=60)
    parser.add_argument("--days", type=int, default=30)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--model-url", default="http://127.0.0.1:8001")
    parser.add_argument("--config", type=Path, default=ROOT / "IHMS-admin/src/main/resources/application.yml")
    parser.add_argument("--mysql", type=Path, default=Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "MySQL/MySQL Server 8.0/bin/mysql.exe")
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    parser.add_argument("--database")
    parser.add_argument("--user")
    parser.add_argument("--password-env")
    run(parser.parse_args())
