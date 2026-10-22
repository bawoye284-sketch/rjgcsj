"""Restore this snapshot into a NEW MySQL database and verify every table.

Python standard library only. Existing databases are always refused.
"""
import argparse
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql', help='Full path to mysql.exe, if not on PATH')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=3306)
    parser.add_argument('--user', default='root')
    parser.add_argument('--database', default='personal_health')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,63}', args.database):
        parser.error('Database name must start with a letter and contain only ASCII letters, digits or underscores.')
    exe = args.mysql or shutil.which('mysql') or shutil.which('mysql.exe')
    if not exe:
        candidate = Path(r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe')
        if candidate.exists():
            exe = str(candidate)
        else:
            parser.error('mysql client not found; supply --mysql with its full path.')
    folder = Path(__file__).resolve().parent
    manifest = json.loads((folder / 'database_manifest.json').read_text(encoding='utf-8'))
    dump = folder / manifest['dump_file']
    if hashlib.sha256(dump.read_bytes()).hexdigest() != manifest['dump_sha256']:
        raise RuntimeError('SQL checksum mismatch. Obtain an intact copy of the handoff package.')
    env = os.environ.copy()
    env['MYSQL_PWD'] = getpass.getpass(f'MySQL password for {args.user}@{args.host}: ')
    base = [str(exe), f'--host={args.host}', f'--port={args.port}', f'--user={args.user}', '--connect-timeout=10', '--default-character-set=utf8mb4']

    def query(sql, database=None, xml=False):
        cmd = [*base, '--batch', '--skip-column-names']
        if database:
            cmd.append('--database=' + database)
        if xml:
            cmd.append('--xml')
        res = subprocess.run(cmd, input=sql.encode('utf-8'), env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if res.returncode:
            raise RuntimeError(res.stderr.decode('utf-8', 'replace'))
        return res.stdout

    exists = int(query(f"SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{args.database}';"))
    if exists:
        raise RuntimeError(f'Database {args.database!r} already exists. Use a different --database name; this tool never overwrites an existing database.')
    query(f'CREATE DATABASE `{args.database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')
    print('Created new database:', args.database)
    with dump.open('rb') as stream:
        res = subprocess.run([*base, '--database=' + args.database], stdin=stream, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if res.returncode:
        raise RuntimeError('Import failed; partial data remains in the NEW database for diagnosis. ' + res.stderr.decode('utf-8', 'replace'))
    actual_tables = query("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;", args.database).decode().splitlines()
    expected_tables = [row['table'] for row in manifest['tables']]
    if actual_tables != expected_tables:
        raise RuntimeError('Restored table list does not match the manifest.')
    for row in manifest['tables']:
        name = row['table']
        count = int(query(f'SELECT COUNT(*) FROM `{name}`;', args.database))
        columns = int(query(f"SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{name}';", args.database))
        tree = ET.fromstring(query(f'SELECT * FROM `{name}`;', args.database, xml=True))
        records = []
        for item in tree.findall('row'):
            fields = [(f.attrib['name'], None if f.attrib.get('{http://www.w3.org/2001/XMLSchema-instance}nil') == 'true' else (f.text or '')) for f in item.findall('field')]
            records.append(json.dumps(fields, ensure_ascii=False, separators=(',', ':')))
        digest = hashlib.sha256('\n'.join(sorted(records)).encode('utf-8')).hexdigest()
        if count != row['rows'] or columns != row['columns'] or digest != row['logical_sha256']:
            raise RuntimeError('Restored data verification failed for ' + name)
        print(f'  OK {name}: {count} rows, {columns} columns')
    print(f"PASS: {manifest['table_count']} tables, {manifest['column_count']} columns, {manifest['total_rows']} rows. Every logical data hash matches.")
    print('Set MYSQL_URL to this database before starting the backend with the mysql profile.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError) as exc:
        print('ERROR:', exc, file=sys.stderr)
        sys.exit(1)
