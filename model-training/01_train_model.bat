@echo off
chcp 65001 >nul
set "PYTHON=%~dp0..\.venv\Scripts\python.exe"
if not exist "%PYTHON%" (
    echo 未找到项目根目录的 .venv\Scripts\python.exe，请先在 PyCharm 中创建虚拟环境并安装 requirements.txt。
    pause
    exit /b 1
)
"%PYTHON%" "%~dp0train_five_score_model.py"
if errorlevel 1 (
    pause
    exit /b 1
)
"%PYTHON%" "%~dp0plot_model_evaluation.py"
pause
