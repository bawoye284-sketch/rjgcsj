"""Import audited NHEFS evaluation participants as source-labelled sample users."""

import argparse
from collections import defaultdict
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import urlsplit
import csv

ROOT = Path(__file__).resolve().parents[2]
CHRONIC = ROOT / "chronic-disease"
CODES = ["hypertension", "diabetes", "stroke", "heart_attack", "osteoporosis"]
FIELDS = ["age", "sex_male", "weight_kg", "self_rated_health", "smoking_current", "drinking_any",
          "exercise_regular", "high_cholesterol_history", "health_worsened", "weight_gain_10lb",
          "weight_loss_10lb", "hip_fracture_history"]
SOURCE_NAME = "NHEFS"
PREFIX = "nhefs_"
# Same local sample password convention as the project's existing seeded accounts.
SAMPLE_PASSWORD = "123456"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def json_text(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))


def sample_names(groups, reserved=()):
    """Assign unique fictional Chinese display names, stable for a given cohort."""
    surnames = list("赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦许何吕施张孔曹严华金魏陶姜谢邹苏潘葛范彭鲁韦马方任袁唐罗梁宋邓许刘徐高林郭")
    given = {
        1: "建国 建军 志强 国华 德明 文华 志远 明辉 国栋 永安 学文 正平 德华 建华 文斌 振华 志成 世荣 光明 长青 宏伟 忠良 国平 明德 文清 志刚 振兴 立新 海生 建平 德荣 永昌 庆华 世杰 安民 福生 瑞祥 伟民 文山 景明".split(),
        0: "秀英 桂兰 淑芬 玉兰 秀芳 丽华 春梅 秀珍 淑华 玉梅 惠芳 秀云 兰英 桂英 素珍 淑兰 美华 月英 雪梅 文秀 丽芳 秋兰 玉琴 春兰 惠兰 桂珍 瑞芳 雅琴 静怡 慧敏 婉清 美娟 秀莲 春燕 惠珍 玉华 素兰 淑琴 云霞 晓梅".split(),
    }
    used, names = set(reserved), {}
    for participant in sorted(groups):
        sex = groups[participant][0]["features"].get("sex_male")
        pool = given.get(sex, given[0] + given[1])
        candidates = set(surname + name for surname in surnames for name in pool) - used
        if not candidates:
            raise ValueError("No unique sample display names remain")
        name = min(candidates, key=lambda candidate: hashlib.sha256(
            ("nhefs-display-name-v1:" + participant + ":" + candidate).encode("utf-8")).digest())
        names[participant] = name
        used.add(name)
    return names


def literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, (int, float)):
        if not math.isfinite(value):
            raise ValueError("Non-finite source value")
        return str(value)
    # Existing account columns use utf8mb4_general_ci, while MySQL 8 defaults
    # converted strings to utf8mb4_0900_ai_ci. Explicit coercion keeps IN/equality
    # checks compatible without changing any existing table's collation.
    return "(CONVERT(0x" + str(value).encode("utf-8").hex() + " USING utf8mb4) COLLATE utf8mb4_general_ci)"


def resolve(value):
    def substitute(match):
        key, separator, default = match.group(1).partition(":")
        if key not in os.environ and not separator:
            raise ValueError("Missing configuration environment variable: " + key)
        return os.environ.get(key, default)
    return re.sub(r"\$\{([^}]+)\}", substitute, str(value))


def connection(args):
    if args.password_env:
        if args.password_env not in os.environ:
            raise ValueError("Password environment variable is not set")
        return args.host or "localhost", args.port or 3306, args.database or "personal_health", args.user or "root", os.environ[args.password_env]
    try:
        import yaml
    except ImportError as error:
        raise ValueError("Reading application.yml requires PyYAML; alternatively use --password-env and connection flags") from error
    config = yaml.safe_load(args.config.read_text(encoding="utf-8"))
    ds = config["spring"]["datasource"]
    address = resolve(ds["url"])
    if not address.startswith("jdbc:mysql://"):
        raise ValueError("Only the configured MySQL datasource is supported")
    uri = urlsplit(address[len("jdbc:"):])
    return args.host or uri.hostname, args.port or uri.port or 3306, args.database or uri.path.lstrip("/"), args.user or resolve(ds["username"]), resolve(ds.get("password", ""))


class Database:
    def __init__(self, executable, settings):
        self.host, self.port, self.name, user, password = settings
        if self.host not in {"localhost", "127.0.0.1", "::1"}:
            raise ValueError("This importer is restricted to the local database")
        self.env = os.environ.copy()
        self.env["MYSQL_PWD"] = password
        self.command = [str(executable), "--batch", "--raw", "--skip-column-names", "--default-character-set=utf8mb4",
                        "--connect-timeout=10", "--host=" + self.host, "--port=" + str(self.port),
                        "--user=" + user, "--database=" + self.name]

    def query(self, sql):
        completed = subprocess.run(self.command, input=sql, encoding="utf-8", capture_output=True, env=self.env, timeout=180)
        if completed.returncode:
            # Error codes aid diagnosis without echoing SQL, credentials or source payloads.
            error = re.search(r"ERROR\s+\d+\s+\([^)]+\)", completed.stderr)
            raise RuntimeError("MySQL operation failed: " + (error.group(0) if error else "client execution error"))
        return completed.stdout.strip()

    def objects(self, sql):
        return [json.loads(line) for line in self.query(sql).splitlines() if line.strip()]


def run(args):
    source = CHRONIC / "data/nhefs/processed/cohorts.jsonl"
    source_hash = digest(source)
    audit = json.loads((CHRONIC / "reports/nhefs/cohort_audit.json").read_text(encoding="utf-8"))
    if source_hash != audit["cohorts_sha256"]:
        raise ValueError("Source cohort differs from the audited cohort")
    groups = defaultdict(list)
    for line in source.read_text(encoding="utf-8").splitlines():
        row = json.loads(line)
        if row["split"] == "evaluation":
            groups[row["participant_id"]].append(row)
    for participant, rows in groups.items():
        if not re.fullmatch(r"[0-9]{5}", participant):
            raise ValueError("Unexpected participant identifier")
        if len({r["disease_code"] for r in rows}) != len(rows):
            raise ValueError("Duplicate disease row")
        if any(r["features"] != rows[0]["features"] for r in rows):
            raise ValueError("Participant features differ across diseases")
        if any(r["label"] not in (0, 1) for r in rows):
            raise ValueError("Invalid source outcome")
    executable = args.mysql or shutil.which("mysql")
    if not executable:
        candidate = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "MySQL/MySQL Server 8.0/bin/mysql.exe"
        if candidate.is_file():
            executable = candidate
    if not executable:
        raise ValueError("mysql client not found; pass --mysql")
    db = Database(executable, connection(args))
    engines = db.objects("SELECT JSON_OBJECT('name',TABLE_NAME,'engine',ENGINE) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('user','elder_disease_profile','elder_disease_history','dataset_user_source');")
    by_name = {r["name"]: r["engine"] for r in engines}
    for name in ["user", "elder_disease_profile", "elder_disease_history"]:
        if by_name.get(name) != "InnoDB":
            raise ValueError("Required transactional table missing: " + name)
    has_source = "dataset_user_source" in by_name
    if has_source and by_name["dataset_user_source"] != "InnoDB":
        raise ValueError("Source table must be transactional")
    existing = {}
    if has_source:
        items = db.objects("SELECT JSON_OBJECT('participant_id',s.participant_id,'hash',s.source_sha256,'user_id',s.user_id,'account',u.user_account,'profiles',(SELECT COUNT(*) FROM elder_disease_profile p WHERE p.user_id=s.user_id),'history',(SELECT COUNT(*) FROM elder_disease_history h WHERE h.user_id=s.user_id)) FROM dataset_user_source s LEFT JOIN `user` u ON u.id=s.user_id WHERE s.dataset='NHEFS';")
        for row in items:
            if row["participant_id"] not in groups:
                continue
            if row["hash"] != source_hash or row["account"] != PREFIX + row["participant_id"] or row["profiles"] != 1 or row["history"] != 5:
                raise ValueError("Existing imported participant differs; manual review required")
            existing[row["participant_id"]] = row["user_id"]
    new = sorted(set(groups) - set(existing))
    if new:
        accounts = ",".join(literal(PREFIX + participant) for participant in new)
        if int(db.query("SELECT COUNT(*) FROM `user` WHERE user_account IN (" + accounts + ");")):
            raise ValueError("An account name is already used without matching import provenance")
    print(json_text({"target_database": db.name, "source_split": "evaluation", "disease_records": sum(map(len, groups.values())),
                     "participants": len(groups), "new_users": len(new), "already_imported": len(existing), "apply": args.apply}), flush=True)
    if not args.apply:
        return
    if not has_source:
        db.query("""CREATE TABLE dataset_user_source (
 user_id INT UNSIGNED NOT NULL PRIMARY KEY,
 dataset VARCHAR(32) NOT NULL,
 participant_id VARCHAR(32) NOT NULL,
 source_split VARCHAR(32) NOT NULL,
 source_sha256 CHAR(64) NOT NULL,
 source_payload_json LONGTEXT NOT NULL,
 imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_dataset_participant (dataset,participant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Imported dataset provenance and original outcomes, separate from clinical history';""")
    stamp = datetime.now(timezone.utc).strftime("nhefs-evaluation-%Y%m%dT%H%M%SZ")
    output = CHRONIC / "reports/database-import" / stamp
    output.mkdir(parents=True)
    statements = ["SET NAMES utf8mb4;", "SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION';",
                  "CREATE TEMPORARY TABLE import_guard (ok TINYINT NOT NULL CHECK(ok=1));",
                  "INSERT INTO import_guard VALUES (IF(GET_LOCK('ihms_nhefs_evaluation_import',10)=1,1,0));",
                  "START TRANSACTION;"]
    password_hash = hashlib.md5(hashlib.md5(SAMPLE_PASSWORD.encode()).hexdigest().encode()).hexdigest()
    reserved_names = [row["name"] for row in db.objects("SELECT JSON_OBJECT('name',user_name) FROM `user` WHERE user_name IS NOT NULL;")]
    names = sample_names({participant: groups[participant] for participant in new}, reserved_names)
    for participant in new:
        rows = groups[participant]
        features = rows[0]["features"]
        account = PREFIX + participant
        statements.append("INSERT INTO import_guard SELECT IF(COUNT(*)=0,1,0) FROM `user` WHERE user_account=" + literal(account) + ";")
        values = [account, names[participant], password_hash, 2, 0, 0]
        statements.append("INSERT INTO `user` (user_account,user_name,user_pwd,user_role,is_login,is_word,create_time) VALUES (" + ",".join(map(literal, values)) + ",CURRENT_TIMESTAMP);")
        statements.append("SET @nhefs_user_id=LAST_INSERT_ID();")
        statements.append("INSERT INTO elder_disease_profile (user_id," + ",".join(FIELDS) + ",profile_revision) VALUES (@nhefs_user_id," + ",".join(literal(features.get(f)) for f in FIELDS) + ",1);")
        for code in CODES:
            statements.append("INSERT INTO elder_disease_history (user_id,disease_code,history_status,diagnosed_year,source) VALUES (@nhefs_user_id," + literal(code) + "," + literal(features.get("history_" + code)) + ",NULL,'NHEFS_1987');")
        payload = {"dataset": SOURCE_NAME, "participant_id": participant, "source_split": "evaluation",
                   "kind": "historical_dataset_sample", "baseline_features": features, "cohort_rows": rows,
                   "notes": "Historical baseline questionnaire; follow-up outcomes are not baseline history or model predictions"}
        statements.append("INSERT INTO dataset_user_source (user_id,dataset,participant_id,source_split,source_sha256,source_payload_json) VALUES (@nhefs_user_id,'NHEFS'," + literal(participant) + ",'evaluation'," + literal(source_hash) + "," + literal(json_text(payload)) + ");")
    expected = len(groups)
    statements += ["INSERT INTO import_guard SELECT IF(COUNT(*)=" + str(expected) + ",1,0) FROM dataset_user_source s JOIN `user` u ON u.id=s.user_id JOIN elder_disease_profile p ON p.user_id=u.id WHERE s.dataset='NHEFS' AND s.source_sha256=" + literal(source_hash) + ";",
                   "INSERT INTO import_guard SELECT IF(COUNT(*)=" + str(expected*5) + ",1,0) FROM elder_disease_history h JOIN dataset_user_source s ON s.user_id=h.user_id WHERE s.dataset='NHEFS' AND s.source_sha256=" + literal(source_hash) + ";",
                   "COMMIT;", "DO RELEASE_LOCK('ihms_nhefs_evaluation_import');"]
    # A batch error closes the connection and rolls back the uncommitted InnoDB inserts.
    db.query("\n".join(statements))
    records = db.objects("SELECT JSON_OBJECT('participant_id',s.participant_id,'user_id',s.user_id,'account',u.user_account,'name',u.user_name,'disease_rows',JSON_LENGTH(JSON_EXTRACT(s.source_payload_json,'$.cohort_rows'))) FROM dataset_user_source s JOIN `user` u ON u.id=s.user_id WHERE s.dataset='NHEFS' AND s.source_sha256=" + literal(source_hash) + " ORDER BY s.participant_id;")
    if len(records) != expected or sum(r["disease_rows"] for r in records) != sum(map(len, groups.values())):
        raise RuntimeError("Post-import verification failed; inspect source records before rerunning")
    if digest(source) != source_hash:
        raise RuntimeError("Source cohort changed during import")
    with (output / "users.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["participant_id", "user_id", "account", "name", "disease_rows"])
        writer.writeheader()
        writer.writerows(records)
    result = {"status": "completed", "database": db.name, "dataset": SOURCE_NAME, "source_split": "evaluation",
              "source_sha256": source_hash, "participants": expected, "disease_records": sum(map(len, groups.values())),
              "new_users": len(new), "skipped_existing_users": len(existing), "profiles": expected, "history_rows": expected*5,
              "account_prefix": PREFIX, "default_sample_password": SAMPLE_PASSWORD,
              "source_table": "dataset_user_source", "existing_business_records_overwritten": False,
              "future_outcomes_separate_from_baseline": True, "missing_fields_preserved": True,
              "weights_modified": False, "training_split_modified": False,
              "doctor_associations_created": False, "prediction_records_created": False,
              "import_code_sha256": digest(Path(__file__))}
    (output / "import_manifest.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json_text(result), flush=True)
    print("Import report: " + str(output), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Commit inserts; without this flag only preview")
    parser.add_argument("--config", type=Path, default=ROOT / "IHMS-admin/src/main/resources/application.yml")
    parser.add_argument("--mysql", type=Path)
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    parser.add_argument("--database")
    parser.add_argument("--user")
    parser.add_argument("--password-env", help="Read password from this environment variable instead of application.yml")
    args = parser.parse_args()
    try:
        run(args)
    except (ValueError, RuntimeError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error))
