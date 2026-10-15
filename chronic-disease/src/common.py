"""Shared paths and reproducible artifact I/O for the independent project."""

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data" / "nhefs"
PROCESSED = DATA / "processed"
REPORTS = ROOT / "reports"
MODELS = ROOT / "models"
TARGET_CONFIG = ROOT / "config" / "nhefs_disease_targets.json"
SEED = 20261015
OPTIONAL_PROFILE_FIELDS = ["weight_kg", "self_rated_health", "smoking_current", "drinking_any", "exercise_regular"]


def enough_profile_information(profile):
    return profile.get("sex_male") is not None and sum(profile.get(name) is not None for name in OPTIONAL_PROFILE_FIELDS) >= 2


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    try:
        temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2,
                                        allow_nan=False) + "\n", encoding="utf-8")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def targets():
    config = read_json(TARGET_CONFIG)
    by_code = {target["code"]: target for target in config["targets"]}
    return [by_code[code] for code in config["first_version_target_codes"]]


def write_jsonl(path, rows):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    try:
        with temporary.open("w", encoding="utf-8") as stream:
            for row in rows:
                stream.write(json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def read_jsonl(path):
    with Path(path).open(encoding="utf-8") as stream:
        return [json.loads(line) for line in stream]


def assigned_split(participant_id):
    """Outcome-independent split shared by every disease and frozen by ID."""
    key = f"{SEED}:{participant_id}".encode("ascii")
    return "evaluation" if int(hashlib.sha256(key).hexdigest()[:8], 16) % 5 == 0 else "development"
