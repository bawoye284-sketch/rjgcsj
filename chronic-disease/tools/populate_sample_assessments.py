"""Complete sample-only simulated inputs and save real predictions through the backend."""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import random
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
import uuid

from import_nhefs_users import CHRONIC, ROOT, Database, connection, json_text, literal

BATCH = "nhefs-simulated-v1"
NOTE = "本记录包含随机生成的模拟补充资料，仅用于系统演示。"
FIELDS = {"age": "age", "sexMale": "sex_male", "weightKg": "weight_kg", "selfRatedHealth": "self_rated_health",
          "smokingCurrent": "smoking_current", "drinkingAny": "drinking_any", "exerciseRegular": "exercise_regular",
          "highCholesterolHistory": "high_cholesterol_history", "healthWorsened": "health_worsened",
          "weightGain10lb": "weight_gain_10lb", "weightLoss10lb": "weight_loss_10lb", "hipFractureHistory": "hip_fracture_history"}
CODES = ["hypertension", "diabetes", "stroke", "heart_attack", "osteoporosis"]


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2) + "\n", encoding="utf-8")
    # OneDrive/antivirus can hold a recently written JSON file briefly on Windows.
    for attempt in range(10):
        try:
            temporary.replace(path)
            return
        except PermissionError:
            if attempt == 9:
                raise
            time.sleep(.1 * (attempt + 1))


def api(base, path, token=None, body=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["token"] = token
    payload = None if body is None else json_text(body).encode("utf-8")
    for attempt in range(3):
        request = Request(base + path, data=payload, headers=headers, method="GET" if body is None else "POST")
        try:
            with urlopen(request, timeout=90) as response:
                value = json.loads(response.read().decode("utf-8"))
            if value.get("code") != 200:
                raise RuntimeError("Backend rejected " + path + ": " + str(value.get("message", value.get("msg", "application error"))))
            return value.get("data")
        except HTTPError as error:
            if error.code not in (502, 503, 504) or attempt == 2:
                raise RuntimeError("Backend HTTP " + str(error.code) + " at " + path) from None
        except (URLError, TimeoutError):
            if attempt == 2:
                raise RuntimeError("Backend connection failed at " + path) from None
        time.sleep(attempt + 1)


def login(base, account):
    password = hashlib.md5(hashlib.md5(b"123456").hexdigest().encode()).hexdigest()
    result = api(base, "/user/login", body={"userAccount": account, "userPwd": password})
    if not isinstance(result, dict) or not result.get("token"):
        raise RuntimeError("Sample login failed")
    return result["token"]


def build_plan(user, current, basic, history, version):
    seed = int(hashlib.sha256((BATCH + ":" + user["participant"]).encode()).hexdigest()[:16], 16)
    rng = random.Random(seed)
    profile = dict(current["profile"])
    disease_history = dict(current["diseaseHistory"])
    generated = {}
    for key in FIELDS:
        if profile.get(key) is not None:
            continue
        if key == "age":
            raise ValueError("Original participant age is required")
        elif key == "sexMale":
            value = rng.randint(0, 1)
        elif key == "weightKg":
            value = round(rng.uniform(45, 90), 3)
        elif key == "selfRatedHealth":
            value = rng.choice([1, 2, 2, 3, 3, 4, 5])
        elif key == "weightGain10lb" and profile.get("weightLoss10lb") == 1:
            value = 0
        elif key == "weightLoss10lb" and profile.get("weightGain10lb") == 1:
            value = 0
        else:
            value = int(rng.random() < .2)
        profile[key] = value
        generated["profile." + key] = value
    if profile["weightGain10lb"] == profile["weightLoss10lb"] == 1:
        raise ValueError("Existing weight-change answers conflict; original fields were not changed")
    for code in CODES:
        if disease_history.get(code) is None:
            value = int(rng.random() < .15)
            disease_history[code] = value
            generated["diseaseHistory." + code] = value
    basic = dict(basic or {})
    basic_profile = {"age": profile["age"], "sexMale": profile["sexMale"], "bmi": basic.get("bmi")}
    if basic_profile["bmi"] is None:
        basic_profile["bmi"] = round(max(16., min(36., rng.gauss(24.5, 3.8))), 2)
        generated["basicProfile.bmi"] = basic_profile["bmi"]
    # These ranges only create varied software-demo inputs; they are not a clinical simulation model.
    systolic = round(rng.uniform(95, 178), 1)
    candidates = {"systolicPressure": systolic,
                  "diastolicPressure": round(rng.uniform(58, min(106, systolic - 25)), 1),
                  "heartRate": round(rng.uniform(52, 115), 1), "spo2": round(rng.uniform(91, 100), 1),
                  "temperature": round(rng.uniform(35.9, 38.2), 1), "respiratoryRate": round(rng.uniform(12, 27), 1)}
    previous = history[-1] if history else {}
    measurement = {}
    for key, value in candidates.items():
        measurement[key] = previous.get(key)
        if measurement[key] is None:
            measurement[key] = value
            generated["measurement." + key] = value
    if measurement["diastolicPressure"] >= measurement["systolicPressure"]:
        raise ValueError("Existing measurement is inconsistent; manual review required")
    disease_input = {"profile": profile, "diseaseHistory": disease_history, "diagnosisYears": current["diagnosisYears"]}
    signature = hashlib.sha256(json_text({"disease": disease_input, "basic": basic_profile, "measurement": measurement}).encode()).hexdigest()
    return {"batch": BATCH, "user_id": user["id"], "account": user["account"], "participant_id": user["participant"],
            "created_at_utc": datetime.now(timezone.utc).isoformat(), "data_kind": "dataset_with_simulated_supplements",
            "note": NOTE, "seed": seed, "original_disease_profile": current, "disease_input": disease_input,
            "basic_profile": basic_profile, "measurement": measurement, "generated_fields": generated,
            "model_version": version, "profile_revision": None,
            "personal_request_id": str(uuid.uuid5(uuid.NAMESPACE_URL, BATCH + ":personal:" + str(user["id"]) + ":" + signature)),
            "disease_request_id": str(uuid.uuid5(uuid.NAMESPACE_URL, BATCH + ":chronic:" + str(user["id"]) + ":" + signature + ":" + version))}


def populate(user, args, db, output, version):
    token = login(args.base_url, user["account"])
    identity = api(args.base_url, "/user/auth", token)
    if int(identity["id"]) != user["id"]:
        raise ValueError("Login account belongs to a different user")
    plan_file = output / "plans" / (str(user["id"]) + ".json")
    if plan_file.exists():
        plan = json.loads(plan_file.read_text(encoding="utf-8"))
        if plan["user_id"] != user["id"] or plan["model_version"] != version:
            raise ValueError("Saved simulation plan differs from current target/model")
    else:
        current = api(args.base_url, "/disease-risk/profile", token)
        basic = api(args.base_url, "/personalized-risk/profile", token)
        history = api(args.base_url, "/personalized-risk/history?days=90", token)
        plan = build_plan(user, current, basic, history, version)
        write_json(plan_file, plan)
    # Preserve the historical source data; add a separate labelled simulation branch before any writes.
    db.query("UPDATE dataset_user_source SET source_payload_json=JSON_SET(source_payload_json,'$.simulation',CAST(" +
             literal(json_text(plan)) + " AS JSON)) WHERE dataset='NHEFS' AND user_id=" + str(user["id"]) + ";")
    current = api(args.base_url, "/disease-risk/profile", token)
    intended = plan["disease_input"]
    equal = all(current[key] == intended[key] for key in ["profile", "diseaseHistory", "diagnosisYears"])
    if not equal:
        original = plan["original_disease_profile"]
        if any(current[key] != original[key] for key in ["profile", "diseaseHistory", "diagnosisYears"]):
            raise ValueError("Profile was edited after the simulation plan; refusing to replace it")
        current = api(args.base_url, "/disease-risk/profile", token, {"revision": current["revision"], **intended})
    plan["profile_revision"] = current["revision"]
    write_json(plan_file, plan)
    personal = api(args.base_url, "/personalized-risk/assess-current", token,
                   {"profile": plan["basic_profile"], "measurement": plan["measurement"], "requestId": plan["personal_request_id"]})
    chronic = api(args.base_url, "/disease-risk/assess", token,
                  {"profileRevision": current["revision"], "requestId": plan["disease_request_id"]})
    if chronic["modelVersion"] != version or len(chronic["results"]) != 5:
        raise ValueError("Unexpected chronic model response")
    personal_id, chronic_id = int(personal["assessmentId"]), chronic["id"]
    if not 0 <= float(personal["groupRiskProbability"]) <= 1:
        raise ValueError("Invalid personalized probability")
    # Record origin in the same saved snapshots/results used by the normal history pages.
    personal_row = db.objects("SELECT JSON_OBJECT('snapshot',JSON_EXTRACT(input_snapshot_json,'$'),'reasons',JSON_EXTRACT(reasons_json,'$')) FROM personalized_risk_assessment WHERE user_id=" + str(user["id"]) + " AND id=" + str(personal_id) + ";")[0]
    chronic_row = db.objects("SELECT JSON_OBJECT('snapshot',JSON_EXTRACT(input_snapshot_json,'$')) FROM disease_prediction_assessment WHERE user_id=" + str(user["id"]) + " AND id=" + literal(chronic_id) + ";")[0]
    origin = {"type": plan["data_kind"], "batch": BATCH, "note": NOTE,
              "simulated_fields": sorted(plan["generated_fields"])}
    p_snapshot, c_snapshot = personal_row["snapshot"], chronic_row["snapshot"]
    p_snapshot["dataOrigin"] = origin
    c_snapshot["dataOrigin"] = origin
    reasons = personal_row["reasons"] or []
    if NOTE not in reasons:
        reasons.append(NOTE)
    c_json = json_text(c_snapshot)
    plan["personal_assessment_id"] = personal_id
    plan["chronic_assessment_id"] = chronic_id
    statements = ["START TRANSACTION;",
                  "UPDATE personalized_risk_assessment SET input_snapshot_json=" + literal(json_text(p_snapshot)) + ",reasons_json=" + literal(json_text(reasons)) + " WHERE user_id=" + str(user["id"]) + " AND id=" + str(personal_id) + ";",
                  "UPDATE disease_prediction_assessment SET input_snapshot_json=" + literal(c_json) + ",input_sha256=" + literal(hashlib.sha256(c_json.encode()).hexdigest()) + " WHERE user_id=" + str(user["id"]) + " AND id=" + literal(chronic_id) + ";",
                  "UPDATE disease_prediction_result SET reason=CASE WHEN LOCATE(" + literal(NOTE) + ",COALESCE(reason,''))>0 THEN reason ELSE CONCAT(COALESCE(reason,''),CASE WHEN COALESCE(reason,'')='' THEN '' ELSE '；' END," + literal(NOTE) + ") END WHERE assessment_id=" + literal(chronic_id) + ";",
                  "UPDATE dataset_user_source SET source_payload_json=JSON_SET(source_payload_json,'$.simulation',CAST(" + literal(json_text(plan)) + " AS JSON)) WHERE dataset='NHEFS' AND user_id=" + str(user["id"]) + ";"]
    for code in CODES:
        history_source = "simulated" if "diseaseHistory." + code in plan["generated_fields"] else "NHEFS_1987"
        statements.append("UPDATE elder_disease_history SET source=" + literal(history_source) + " WHERE user_id=" + str(user["id"]) + " AND disease_code=" + literal(code) + ";")
    statements.append("COMMIT;")
    db.query("\n".join(statements))
    write_json(plan_file, plan)
    result = {"user_id": user["id"], "account": user["account"], "name": user["name"],
              "personal_assessment_id": personal_id, "chronic_assessment_id": chronic_id,
              "personal_model": personal.get("modelVersion"), "chronic_model": chronic["modelVersion"],
              "risk_level": personal["riskLevel"], "disease_statuses": dict(Counter(r["status"] for r in chronic["results"])),
              "simulated_fields": len(plan["generated_fields"]), "data_kind": plan["data_kind"]}
    write_json(output / "results" / (str(user["id"]) + ".json"), result)
    return result


def run(args):
    parsed = urlsplit(args.base_url)
    if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost"}:
        raise ValueError("Only the local backend is supported")
    db = Database(args.mysql, connection(args))
    users = db.objects("SELECT JSON_OBJECT('id',u.id,'account',u.user_account,'name',u.user_name,'participant',s.participant_id) FROM dataset_user_source s JOIN `user` u ON u.id=s.user_id WHERE s.dataset='NHEFS' AND s.source_split='evaluation' ORDER BY s.participant_id;")
    if not users or any(u["account"] != "nhefs_" + u["participant"] for u in users):
        raise ValueError("No valid imported NHEFS sample users")
    token = login(args.base_url, users[0]["account"])
    models = api(args.base_url, "/disease-risk/models", token)
    version = models["model_version"]
    if not models.get("enabled") or not (models.get("runtime") or {}).get("ready"):
        raise ValueError("Backend models are not ready")
    print(json_text({"sample_users": len(users), "database": db.name, "model_version": version,
                     "apply": args.apply, "batch": BATCH, "data_kind": "dataset_with_simulated_supplements"}), flush=True)
    if not args.apply:
        return
    output = CHRONIC / "reports/database-import" / BATCH
    output.mkdir(parents=True, exist_ok=True)
    results, failures = [], []
    total_users = len(users)
    if args.retry_failed:
        previous = json.loads((output / "summary.json").read_text(encoding="utf-8"))
        if previous["batch"] != BATCH or previous["database"] != db.name or previous["chronic_model_version"] != version:
            raise ValueError("Retry summary belongs to a different batch/database/model")
        failed_ids = {item["user_id"] for item in previous["failures"]}
        results = previous["results"]
        users = [user for user in users if user["id"] in failed_ids]
        if len(users) != len(failed_ids) or len(results) + len(users) != total_users:
            raise ValueError("Retry summary does not cover the imported users")
    with ThreadPoolExecutor(max_workers=max(1, min(4, args.workers))) as executor:
        pending = {executor.submit(populate, user, args, db, output, version): user for user in users}
        for i, task in enumerate(as_completed(pending), 1):
            user = pending[task]
            try:
                results.append(task.result())
            except Exception as error:
                failure = {"user_id": user["id"], "account": user["account"], "error": str(error)}
                failures.append(failure)
                print(json_text(failure), flush=True)
            if i % 20 == 0 or i == len(users):
                print(f"Progress {i}/{len(users)}: completed={len(results)}, failed={len(failures)}", flush=True)
    status = {"status": "completed" if not failures else "partial", "batch": BATCH, "database": db.name,
              "sample_users": total_users, "completed_users": len(results), "failed_users": len(failures),
              "chronic_model_version": version, "data_kind": "dataset_with_simulated_supplements",
              "note": NOTE, "generated_from_followup_labels": False, "weights_modified": False,
              "failures": failures, "results": sorted(results, key=lambda r: r["user_id"])}
    write_json(output / "summary.json", status)
    print(json_text({key: value for key, value in status.items() if key not in {"results", "failures"}}), flush=True)
    if failures:
        raise RuntimeError("Some sample assessments failed; rerun resumes saved plans and idempotent request IDs")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--retry-failed", action="store_true", help="Resume only failed users from the last summary")
    parser.add_argument("--base-url", default="http://127.0.0.1:21090/api/personal-heath/v1.0")
    parser.add_argument("--config", type=Path, default=ROOT / "IHMS-admin/src/main/resources/application.yml")
    parser.add_argument("--mysql", type=Path, default=Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "MySQL/MySQL Server 8.0/bin/mysql.exe")
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    parser.add_argument("--database")
    parser.add_argument("--user")
    parser.add_argument("--password-env")
    parser.add_argument("--workers", type=int, default=3)
    args = parser.parse_args()
    try:
        run(args)
    except (ValueError, RuntimeError) as error:
        raise SystemExit(str(error))
