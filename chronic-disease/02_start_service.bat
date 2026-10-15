@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
    echo Please create this project's Python environment first. See README.md.
    exit /b 1
)
set PYTHONDONTWRITEBYTECODE=1
if not defined CHRONIC_MODEL_VERSION set CHRONIC_MODEL_VERSION=nhefs5-opt-20261015-v2
echo Starting chronic disease research model: %CHRONIC_MODEL_VERSION% on 127.0.0.1:8002
".venv\Scripts\python.exe" -m uvicorn api:app --app-dir src --host 127.0.0.1 --port 8002
