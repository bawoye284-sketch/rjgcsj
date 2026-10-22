"""Export the source read-only and verify it in a newly created isolated schema."""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs' / 'handoff_20261021' / 'database'
BIN = Path(r'C:\Program Files\MySQL\MySQL Server 8.0\bin')
SOURCE = 'personal_health'
SQL_NAME = 'personal_health_20261021.sql'
env = os.environ.copy()
config = (ROOT / 'IHMS-admin/src/main/resources/application.yml').read_text(encoding='utf-8')
env['MYSQL_PWD'] = env.get('MYSQL_PASSWORD') or re.search(r'password:\s*\$\{MYSQL_PASSWORD:([^}]*)\}', config).group(1)
USER = env.get('MYSQL_USERNAME', 'root')
BASE = ['--host=127.0.0.1', '--port=3306', f'--user={USER}', '--connect-timeout=10', '--default-character-set=utf8mb4']


def mysql(sql, database=None, xml=False):
    cmd = [str(BIN / 'mysql.exe'), *BASE, '--batch', '--skip-column-names']
    if xml:
        cmd.append('--xml')
    if database:
        cmd.append('--database=' + database)
    r = subprocess.run(cmd, input=sql.encode('utf-8'), stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, check=False)
    if r.returncode:
        raise RuntimeError(r.stderr.decode('utf-8', 'replace'))
    return r.stdout


def inventory(database):
    tables = mysql("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;", database).decode().splitlines()
    result = []
    for table in tables:
        quoted = '`' + table.replace('`', '``') + '`'
        raw = mysql('SELECT * FROM ' + quoted + ';', database, xml=True)
        tree = ET.fromstring(raw)
        rows = []
        for row in tree.findall('row'):
            fields = [(f.attrib['name'], None if f.attrib.get('{http://www.w3.org/2001/XMLSchema-instance}nil') == 'true' else (f.text or '')) for f in row.findall('field')]
            rows.append(json.dumps(fields, ensure_ascii=False, separators=(',', ':')))
        rows.sort()
        digest = hashlib.sha256('\n'.join(rows).encode('utf-8')).hexdigest()
        cols = int(mysql('SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=\'' + table + '\';', database))
        result.append({'table': table, 'rows': len(rows), 'columns': cols, 'logical_sha256': digest})
    return result


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    dest = OUT / SQL_NAME
    if dest.exists() and dest.stat().st_size:
        raise RuntimeError('Export already exists; refusing to overwrite.')
    before = inventory(SOURCE)
    version = mysql('SELECT VERSION();').decode().strip()
    dump_base = [arg for arg in BASE if not arg.startswith('--connect-timeout=')]
    cmd = [str(BIN / 'mysqldump.exe'), *dump_base, '--single-transaction', '--quick', '--routines', '--events', '--triggers', '--hex-blob', '--no-tablespaces', '--set-gtid-purged=OFF', '--column-statistics=0', '--skip-add-drop-table', '--skip-add-locks', SOURCE]
    # Python opens the Unicode path; mysqldump writes unmodified UTF-8 bytes.
    with dest.open('wb') as handle:
        dump_result = subprocess.run(cmd, env=env, stdout=handle, stderr=subprocess.PIPE)
    if dump_result.returncode:
        raise RuntimeError('mysqldump failed: ' + dump_result.stderr.decode('utf-8', 'replace'))
    after = inventory(SOURCE)
    if before != after:
        raise RuntimeError('Source changed during export; review before delivery.')
    test_db = 'ihms_handoff_check_' + uuid.uuid4().hex[:12]
    created = False
    try:
        mysql(f'CREATE DATABASE `{test_db}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')
        created = True
        with dest.open('rb') as handle:
            r = subprocess.run([str(BIN / 'mysql.exe'), *BASE, '--database=' + test_db], stdin=handle, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if r.returncode:
            raise RuntimeError('Isolated restore failed: ' + r.stderr.decode('utf-8', 'replace'))
        restored = inventory(test_db)
        if restored != after:
            raise RuntimeError('Restored rows or logical data hash do not match source.')
        # Compare the table definitions; the SHOW output has no schema-qualified names.
        for rec in after:
            table = rec['table']
            if mysql(f'SHOW CREATE TABLE `{table}`;', SOURCE) != mysql(f'SHOW CREATE TABLE `{table}`;', test_db):
                raise RuntimeError('Restored schema differs for ' + table)
        objects = mysql("SELECT 'views',COUNT(*) FROM information_schema.VIEWS WHERE TABLE_SCHEMA=DATABASE() UNION ALL SELECT 'routines',COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() UNION ALL SELECT 'triggers',COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() UNION ALL SELECT 'events',COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA=DATABASE();", SOURCE).decode().splitlines()
        now = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).isoformat(timespec='seconds')
        record = {'exported_at_china': now, 'source_database': SOURCE, 'mysql_version': version, 'dump_file': SQL_NAME, 'dump_bytes': dest.stat().st_size, 'dump_sha256': hashlib.sha256(dest.read_bytes()).hexdigest(), 'table_count': len(after), 'column_count': sum(t['columns'] for t in after), 'total_rows': sum(t['rows'] for t in after), 'tables': after, 'other_objects': dict(line.split('\t') for line in objects), 'verification': {'source_before_after_unchanged': True, 'isolated_restore_passed': True, 'all_table_definitions_match': True, 'all_row_counts_match': True, 'all_logical_data_hashes_match': True, 'temporary_schema': test_db, 'temporary_schema_dropped': False}}
    finally:
        if created and re.fullmatch(r'ihms_handoff_check_[0-9a-f]{12}', test_db):
            mysql(f'DROP DATABASE `{test_db}`;')
    record['verification']['temporary_schema_dropped'] = True
    (OUT / 'database_manifest.json').write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    lines = ['table,rows,columns,logical_sha256'] + [f"{t['table']},{t['rows']},{t['columns']},{t['logical_sha256']}" for t in after]
    (OUT / 'table_counts.csv').write_text('\n'.join(lines) + '\n', encoding='utf-8-sig')
    print(json.dumps({k:v for k,v in record.items() if k not in ('tables',)}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
