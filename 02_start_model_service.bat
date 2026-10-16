@echo off
setlocal
cd /d "%~dp0"
set "PYTHON=%~dp0chronic-disease\.venv\Scripts\python.exe"
if not exist "%PYTHON%" (
    echo Create chronic-disease/.venv first. See chronic-disease/README.md.
    exit /b 1
)
set PYTHONDONTWRITEBYTECODE=1
if not defined CHRONIC_MODEL_VERSION set CHRONIC_MODEL_VERSION=nhefs5-opt-20261015-v2
"%PYTHON%" -B -c "import json,urllib.request,sys; r=json.load(urllib.request.urlopen('http://127.0.0.1:8001/health',timeout=2)); sys.exit(0 if r.get('service')=='unified-health-risk' and r.get('ok') and r.get('chronic',{}).get('model_version')==__import__('os').environ['CHRONIC_MODEL_VERSION'] else 1)" >nul 2>&1
if not errorlevel 1 (
    echo Unified model service is already running on 127.0.0.1:8001.
    exit /b 0
)
echo Starting both model families on 127.0.0.1:8001
echo Chronic model: %CHRONIC_MODEL_VERSION%
"%PYTHON%" -B -m uvicorn unified_app:app --app-dir risk-service --host 127.0.0.1 --port 8001
