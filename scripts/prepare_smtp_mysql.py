"""Generate a clean-room MySQL test database script; never connects to a database.

Only schema definitions and fictional account/config seeds are used. Never copies
measurements, assessments, contacts, events or notification tasks from a database.
"""
import argparse
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def generate(database):
    if not re.fullmatch(r'ihms_smtp_test_[a-z0-9_]{1,40}', database):
        raise ValueError('Database must have an isolated ihms_smtp_test_ name')
    schema = (ROOT / 'IHMS-admin/src/main/resources/demo/schema.sql').read_text(encoding='utf-8')
    data = (ROOT / 'IHMS-admin/src/main/resources/demo/data.sql').read_text(encoding='utf-8')
    tables = {}
    for name, body in re.findall(r'CREATE TABLE IF NOT EXISTS `?(\w+)`?\s*\((.*?)\);', schema + '\n' + data, re.S):
        tables[name] = body
    # Fold the additive H2 columns into the new MySQL CREATE definitions.
    for table, col, definition in re.findall(
            r'ALTER TABLE `?(\w+)`? ADD COLUMN IF NOT EXISTS `?(\w+)`? ([^;]+);', schema):
        if not re.search(r'(?m)^\s*`?' + re.escape(col) + r'`?\s', tables[table]):
            tables[table] += ',\n `' + col + '` ' + definition
    for table, col in [('health_alert_event', 'first_measurement_id'), ('health_alert_event', 'last_measurement_id'),
                       ('health_notification_task', 'measurement_id')]:
        tables[table] = re.sub(r'\b' + col + r' BIGINT NOT NULL', col + ' BIGINT NULL', tables[table])
    tables['personalized_risk_assessment'] += ',\n UNIQUE KEY uk_personal_assessment_request (user_id,request_id)'
    result = ['-- New isolated test database ONLY. Existing database names cause an error.',
              f'CREATE DATABASE `{database}` CHARACTER SET utf8mb4;', f'USE `{database}`;']
    for name, body in tables.items():
        body = re.sub(r'\bCLOB\b', 'LONGTEXT', body, flags=re.I)
        result.append(f'CREATE TABLE `{name}` ({body}) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;')
    # Safe fictional identities, association and existing reference configuration only.
    allowed = {'user', 'doctor', 'doctor_consultation', 'health_model_config'}
    for statement, name in re.findall(r'(INSERT IGNORE INTO `?(\w+)`?[^;]+;)', data):
        if name in allowed:
            result.append(statement)
    result.extend(re.findall(r'INSERT INTO health_alert_rule [^;]+;', schema))
    # Reference seeds/activation table intentionally use the existing project source.
    # The table is already created above; this only supplies missing temp/resp ranges.
    reference = (ROOT / 'sql/health_reference_defaults.sql').read_text(encoding='utf-8')
    reference = re.sub(r'CREATE TABLE IF NOT EXISTS health_reference_activation\s*\(.*?;', '', reference, flags=re.S)
    result.append(reference)
    return '\n\n'.join(result) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    sql = generate(args.database)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8', newline='\n') as output:
        output.write(sql)
    print('Isolated schema generated; no database connection or email was made.')


if __name__ == '__main__':
    main()
