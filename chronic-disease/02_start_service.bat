@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
    echo Please create this project's Python environment first. See README.md.
    exit /b 1
)
set PYTHONDONTWRITEBYTECODE=1
".venv\Scripts\python.exe" -m uvicorn api:app --app-dir src --host 127.0.0.1 --port 8002
