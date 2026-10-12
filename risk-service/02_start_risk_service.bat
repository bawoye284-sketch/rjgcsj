@echo off
chcp 65001 >nul
set "PYTHON=%~dp0..\.venv\Scripts\python.exe"
if not exist "%PYTHON%" (
    echo 未找到项目根目录的 .venv\Scripts\python.exe，请先在 PyCharm 中创建虚拟环境。
    pause
    exit /b 1
)
cd /d "%~dp0"
"%PYTHON%" -m uvicorn app:app --host 127.0.0.1 --port 8001
pause
