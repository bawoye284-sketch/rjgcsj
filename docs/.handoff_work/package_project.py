import hashlib
import json
import os
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/handoff_20261021'
ARCHIVE = ROOT / 'docs/社区老人健康风险评估系统_完整交接包_20261021.zip'
PRUNE = {'.git', '.venv', 'node_modules', '.idea', '.vscode', '.build-tools', 'target', 'dist', '__pycache__', 'logs', '.handoff_work', '.pytest_cache'}
EXCLUDE = {'IHMS-admin/src/main/resources/application.yml', 'IHMS-admin/src/main/resources/application-demo.yml', 'docs/handoff_20261021/package_manifest.json'}


def selected():
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = sorted(d for d in dirs if d not in PRUNE)
        for name in sorted(files):
            p = Path(base) / name
            rel = p.relative_to(ROOT).as_posix()
            if rel in EXCLUDE or name.endswith(('.zip', '.zip.sha256.txt', '.pyc', '.pyo', '.mv.db', '.lock.db', '.trace.db')):
                continue
            if name == '.env' or name.startswith('.env.'):
                continue
            yield p, rel


def main():
    candidates = list(selected())
    records = []
    for p, rel in candidates:
        records.append({'path': 'rjgcsj/' + rel, 'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()})
    manifest = {'created_date_china': '2026-10-21', 'source_root': 'rjgcsj', 'file_count_excluding_this_manifest': len(records), 'uncompressed_bytes_excluding_this_manifest': sum(r['bytes'] for r in records), 'includes': ['source code', 'online model weights and metadata', 'project-local NHEFS original and processed data', 'saved research runs and reports', 'final model document', 'handoff guide', 'MySQL snapshot and restore helper'], 'excluded': ['local credentials in application.yml and application-demo.yml', 'Git history', 'virtual environments', 'node_modules', 'Maven caches', 'build outputs', 'IDE configuration', 'logs', 'H2 runtime files', 'project-external sjj hourly source'], 'files': records}
    manifest_path = OUT / 'package_manifest.json'
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    candidates.append((manifest_path, manifest_path.relative_to(ROOT).as_posix()))
    with zipfile.ZipFile(ARCHIVE, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as z:
        for p, rel in candidates:
            z.write(p, arcname='rjgcsj/' + rel)
    with zipfile.ZipFile(ARCHIVE) as z:
        bad = z.testzip()
        if bad:
            raise RuntimeError('ZIP CRC failed for ' + bad)
        names = set(z.namelist())
        for forbidden in EXCLUDE - {'docs/handoff_20261021/package_manifest.json'}:
            if 'rjgcsj/' + forbidden in names:
                raise RuntimeError('Local credential configuration included')
        for r in records:
            if hashlib.sha256(z.read(r['path'])).hexdigest() != r['sha256']:
                raise RuntimeError('Archive SHA-256 mismatch: ' + r['path'])
    sha = hashlib.sha256(ARCHIVE.read_bytes()).hexdigest()
    ARCHIVE.with_suffix('.zip.sha256.txt').write_text(sha + '  ' + ARCHIVE.name + '\n', encoding='utf-8')
    print(json.dumps({'archive': str(ARCHIVE), 'files': len(candidates), 'bytes': ARCHIVE.stat().st_size, 'sha256': sha, 'all_file_hashes_match': True}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
