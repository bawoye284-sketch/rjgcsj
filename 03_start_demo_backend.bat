@echo off
chcp 65001 >nul
if not exist "%~dp0IHMS-admin\src\main\resources\application.yml" (
    copy /y "%~dp0IHMS-admin\src\main\resources\application.yml.example" "%~dp0IHMS-admin\src\main\resources\application.yml" >nul
)
if not exist "%~dp0IHMS-admin\src\main\resources\application-demo.yml" (
    copy /y "%~dp0IHMS-admin\src\main\resources\application-demo.yml.example" "%~dp0IHMS-admin\src\main\resources\application-demo.yml" >nul
)
cd /d "%~dp0IHMS-admin"
where mvn >nul 2>nul
if errorlevel 1 (
    set "MAVEN=%~dp0IHMS-admin\.build-tools\apache-maven-3.9.16\bin\mvn.cmd"
    if not exist "%~dp0IHMS-admin\.build-tools\apache-maven-3.9.16\bin\mvn.cmd" (
        echo 未找到 Maven。请安装 Maven 并重新打开终端，或在 IntelliJ IDEA 中运行 Application.java，设置程序参数 --spring.profiles.active=demo。
        pause
        exit /b 1
    )
) else (
    set "MAVEN=mvn"
)
echo 正在启动演示后端（H2 本地文件数据），后端将自动加载六个模型，无需单独启动模型服务...
call "%MAVEN%" spring-boot:run "-Dspring-boot.run.profiles=demo" "-Dmaven.repo.local=%~dp0IHMS-admin\.build-tools\repo"
pause
