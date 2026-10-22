import getpass
import json
import os
from pathlib import Path
import re
import runpy
import subprocess
import sys
import uuid

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'docs/handoff_20261021'
os.chdir(ROOT)
cfg=(ROOT/'IHMS-admin/src/main/resources/application.yml').read_text(encoding='utf-8')
password=os.environ.get('MYSQL_PASSWORD') or re.search(r'password:\s*\$\{MYSQL_PASSWORD:([^}]*)\}',cfg).group(1)
env=os.environ.copy();env['MYSQL_PWD']=password
client=str(Path(r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'))
test='ihms_handoff_helper_'+uuid.uuid4().hex[:12]
base=[client,'--host=127.0.0.1','--user=root','--batch','--skip-column-names']
existing=subprocess.run([*base,'--execute='+f"SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{test}';"],env=env,capture_output=True,check=True).stdout.strip()
assert existing==b'0'
getpass.getpass=lambda prompt:password
sys.argv=['restore_database.py','--mysql',client,'--database',test]
print('Testing the delivered restore helper in an isolated schema...',flush=True)
try:
    runpy.run_path(str(OUT/'database/restore_database.py'),run_name='__main__')
finally:
    if re.fullmatch(r'ihms_handoff_helper_[0-9a-f]{12}',test):
        subprocess.run([*base,'--execute='+f'DROP DATABASE IF EXISTS `{test}`;'],env=env,capture_output=True,check=True)
print('Restore helper passed. Testing model load without starting an HTTP server...',flush=True)
model_code="""import asyncio,json,sys
sys.path.insert(0,'risk-service')
import unified_app
async def check():
    async with unified_app.app.router.lifespan_context(unified_app.app):
        result=unified_app.health()
        assert result['ok'] and result['chronic']['ready_count']==5
        assert result['chronic']['model_version']=='nhefs5-opt-20261015-v2'
        print(json.dumps(result,ensure_ascii=False))
asyncio.run(check())
"""
result=subprocess.run([str(ROOT/'chronic-disease/.venv/Scripts/python.exe'),'-B','-X','utf8','-c',model_code],capture_output=True,timeout=120,env=os.environ.copy())
if result.returncode:
    raise RuntimeError(result.stderr.decode('utf-8','replace'))
health=json.loads(result.stdout.decode('utf-8'))
record={'restore_helper_isolated_roundtrip_passed':True,'restore_helper_temporary_schema_removed':True,'offline_six_model_loading_passed':True,'health':health,'note':'Model check invokes application startup and health in process; it does not start or change existing HTTP services.'}
(OUT/'delivery_verification.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(record,ensure_ascii=False,indent=2),flush=True)
