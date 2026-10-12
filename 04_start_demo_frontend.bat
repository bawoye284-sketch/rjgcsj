@echo off
chcp 65001 >nul
cd /d "%~dp0IHMS-view"
where npm.cmd >nul 2>nul
if errorlevel 1 (
    echo 未找到 npm。请先安装 Node.js，安装后重新打开终端。
    pause
    exit /b 1
)
if not exist "node_modules\.bin\vue-cli-service.cmd" (
    echo 首次运行，正在安装前端依赖...
    call npm.cmd ci
    if errorlevel 1 (
        echo 前端依赖安装失败，请查看上方第一条 npm error。
        pause
        exit /b 1
    )
)
echo 正在启动 Vue 前端...
call npm.cmd run dev:compatible
pause
