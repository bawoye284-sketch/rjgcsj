# Windows / VS Code / PyCharm 运行说明

以下路径均相对于下载或克隆后的项目根目录。

演示模式需要 Python 风险服务、Java 后端、Vue 前端三个进程；正式模式还需要 MySQL。模型训练是离线步骤，平时启动网站时不需要重复训练。

## 无 MySQL 演示模式（建议先用它检查页面）

演示模式使用 Java 后端内置的 H2 文件数据库，自动建立与正式模式相同的 20 张表并填入示例用户、资讯、健康记录、医生咨询及风险档案。页面和后端接口保持原样，新增、修改、删除的数据保存在 `IHMS-admin/demo-data/`，重启后仍可查看。正式模式仍使用 MySQL，不受演示配置影响。需要恢复最初演示数据时，先停止 Java 后端，再删除 `IHMS-admin/demo-data/` 中的 H2 数据库文件。

1. 启动 Python 风险服务：双击 `risk-service/02_start_risk_service.bat`，打开 `http://127.0.0.1:8001/health` 确认 `model_ready: true`。模型已预先训练，演示时无需重新训练。
2. 启动 Java 后端：双击项目根目录的 `03_start_demo_backend.bat`。首次运行会从 `.example` 文件生成本机配置，不覆盖已有配置。脚本优先使用 PATH 中的 Maven，找不到时使用 `IHMS-admin/.build-tools/` 中的本地 Maven；该工具目录不会上传到 GitHub，所以在新电脑上需安装 Maven。也可在 IntelliJ IDEA 中打开 `IHMS-admin/pom.xml`，把 JDK 设为 17，工作目录设为 `IHMS-admin`，在 `Application.java` 的运行配置中加入程序参数 `--spring.profiles.active=demo` 后运行。后端使用端口 21090，且不连接 MySQL。
3. 启动 Vue 前端：双击项目根目录的 `04_start_demo_frontend.bat`；脚本首次运行会安装依赖，之后直接启动。也可在新终端进入 `IHMS-view`，先运行 `npm.cmd ci`，安装成功后运行 `npm.cmd run dev:compatible`。浏览器打开 `http://localhost:21091`。

演示登录：用户 `demo_elder` / `123456`，管理员 `demo_admin` / `123456`，医生请在登录页切换“医生登录”，账号 `demo_doctor` / `123456`。演示密码仅用于本地演示。配置模板默认启用 `demo`，直接运行 `Application.java` 也使用 H2。若需要接入真实 MySQL，按后文初始化数据库，并显式使用 `--spring.profiles.active=mysql`。

医疗问答中需要外部大模型的功能仍受独立的 DeepSeek API 配置影响；这与是否启用 MySQL 无关。个性化风险评估使用本地 Python 风险服务，请保持其终端运行。

## 1. 准备环境

- Python：新电脑先在**项目根目录**执行 `py -3.13 -m venv .venv`，再把 VS Code 或 PyCharm 的项目解释器设为 `.venv\Scripts\python.exe`，并按第 3 节安装依赖。已有虚拟环境时无需重建。
- Java：使用 JDK 17。后端是 Spring Boot 2.6.13；不要仅凭 `java -version` 显示 Java 21 就认为该版本已受项目支持。
- Maven：从 GitHub 下载项目后请安装 Maven；也可用 IntelliJ IDEA 打开 `IHMS-admin/pom.xml`，运行 `com.star.Application`。
- Node.js：安装带 npm 的 Node.js，并确保 `npm -version` 能运行。
- MySQL：只有正式模式需要 MySQL 8.0；演示模式不需要安装或启动 MySQL。

VS Code 和 PyCharm 的终端都可输入以下命令；Java 后端请用 Maven 或 IntelliJ IDEA 启动。VS Code 中按 `Ctrl+反引号` 可新建终端。

## 2. 初始化数据库（第一次运行）

在 MySQL Workbench / Navicat 中新建 `personal_health` 数据库，并设为当前数据库。

- **全新数据库**：可执行 `sql/init_mysql_schema_safe.sql`，创建 `personal_health` 和 20 张表；该脚本保留已有表和记录，不负责补齐已有表缺少的字段，也不插入示例账号。`sql/personal_health_with_risk.sql` 是含历史数据的完整脚本，包含 `DROP TABLE`，不要在已有业务数据的库上执行。
- **已有 personal_health 数据库**：先备份。若尚无个性化风险表，执行 `sql/personalized_risk_upgrade.sql`；若已有个性化风险表但缺少呼吸频率等新列，只执行一次 `sql/news2_upgrade_existing.sql`。数据库列 `group_risk_probability` 为兼容旧记录保留，当前存放五项指标模型预警值。

若本机还没有 `IHMS-admin/src/main/resources/application.yml`，先复制同目录的 `application.yml.example` 并去掉文件名末尾的 `.example`。然后把 `spring.datasource.username` 和 `spring.datasource.password` 改成自己 MySQL 的用户名和密码。数据库地址默认为 `localhost:3306/personal_health`。需要外部智能问答时，另填 `deepseek.api-key`。本机配置已被 Git 忽略。

当前完整表结构包含 20 张表、162 个字段。基础版、完整版及升级脚本包含重叠定义，应根据数据库现状选择对应脚本，不要将整个 `sql/` 目录依次导入。表结构初始化与业务数据导入是两个步骤：`init_mysql_schema_safe.sql` 只创建结构，原始完整脚本另含历史业务数据。连接名称（例如 `Elderly Health`）不是数据库名。使用 MySQL 时，在 IDEA 的程序参数填写 `--spring.profiles.active=mysql`；双击 `03_start_demo_backend.bat` 会显式启用 H2 演示模式。

## 3. 安装 Python 依赖（第一次运行）

在 VS Code 或 PyCharm 的**终端**进入项目根目录，执行：

```powershell
& .\.venv\Scripts\python.exe -m pip install -r .\model-training\requirements.txt
& .\.venv\Scripts\python.exe -m pip install -r .\risk-service\requirements.txt
```

当前在线实验模型版本为 `numeric5-future-aggregate-v2-grid`。单纯启动网站不需要先训练。若要重新训练当前模型：

```powershell
& .\.venv\Scripts\python.exe .\model-training\train_five_score_model.py
```

新模型保存到 `model-training/output/news2-five-score/`，重新训练不会自动替换在线模型。确认五项综合分替代标签的测试指标后，在风险服务**停止时**把该目录下的 `five_score_future_model.joblib` 与 `metrics.json` 复制到 `risk-service/model/`，再启动风险服务。这些指标只适用于筛选后的住院数据，不是社区老人健康判断的真实准确率。

## 4. 按顺序启动服务

保持以下终端分别打开，不要在一个终端里依次输入所有启动命令。

1. **MySQL**：确认 MySQL 服务已启动。
2. **风险服务**：双击 `risk-service/02_start_risk_service.bat`，或在根目录执行：

   ```powershell
   Set-Location .\risk-service
   & ..\.venv\Scripts\python.exe -m uvicorn app:app --host 127.0.0.1 --port 8001
   ```

   浏览器打开 `http://127.0.0.1:8001/health`，应看到 `model_ready: true`。
3. **Java 后端**：在另一个终端执行：

   ```powershell
   Set-Location .\IHMS-admin
   mvn spring-boot:run "-Dspring-boot.run.profiles=mysql"
   ```

   也可以用 IntelliJ IDEA 打开 `IHMS-admin/pom.xml`，把项目 JDK 设为 17，运行 `src/main/java/com/star/Application.java`；MySQL 模式的程序参数为 `--spring.profiles.active=mysql`。后端端口是 **21090**。
4. **Vue 前端**：在第三个终端执行：

   ```powershell
   Set-Location .\IHMS-view
   npm.cmd ci
   npm.cmd run dev:compatible
   ```

   `npm.cmd ci` 只需第一次或依赖变化时执行。确认安装成功、出现新的 PowerShell 提示符后，再运行下一行。看到编译成功后，浏览器打开 `http://localhost:21091`。

前端请求 `http://localhost:21090/api/personal-heath/v1.0`，Java 后端再请求 `http://127.0.0.1:8001/predict`；这两个地址已在项目中配置。登录后打开 `/personalized-risk`，先保存基础档案和本次五项生理测量，再点击“立即评估”。吸氧和意识状态暂不使用；呼吸频率未测时由训练集拟合的近邻方法估算，血氧未测时用训练集中位数填补，但不能凭估算值给出“正常”结论。本次测量前 30 天内至少三条既往测量可用于个人近期变化。

若每个终端都从 PyCharm 的项目根目录新开，直接使用各自步骤的命令即可。已有窗口如果位于子目录，先回到项目根目录再执行相应的 `Set-Location`。

## 5. 常见启动问题

- `No module named fastapi`：在项目根目录用第 3 步的 `.venv` Python 安装风险服务依赖；不要用系统 `python` 给另一个解释器安装。
- `mvn` 或 `npm` 无法识别：对应工具未安装或未加入 PATH，安装后重开终端。
- `npm.cmd ci` 下载包时报 404：先确认使用最新项目文件。旧版前端曾依赖已经无法下载的 `vue-vibe@8.0.3`，现已从项目中移除。安装失败时不要直接运行 `npm.cmd run dev:compatible`，否则会提示找不到 `cross-env`。
- 后端提示数据库连接失败：确认 MySQL 已启动、数据库名为 `personal_health`，并检查 `application.yml` 的账号密码。
- 前端能打开但个性化风险评估失败：按顺序查看 8001 风险服务、21090 后端是否启动；前端接口地址在 `IHMS-view/src/utils/request.js`。
- 风险服务报告的模型版本仍是旧版：进程会在启动时加载模型，停止旧进程、确认模型文件已更新后再重启风险服务。

### MySQL80 启动后立即停止，NET HELPMSG 3534

3534是通用服务启动失败提示，先检查MySQL错误日志及控制台启动输出。2026-10-15本机的一次故障中，管理员控制台使用原有`my.ini`和数据目录可正常启动，但Windows服务立即退出；检查发现服务登录账户`NT AUTHORITY\NetworkService`的默认临时目录缺失。补建后，MySQL80服务正常启动并出现3306端口就绪日志。

仅在确认本机服务使用该账户且目录确实缺失时，在管理员PowerShell中补建：

```powershell
New-Item -ItemType Directory -Path 'C:\Windows\ServiceProfiles\NetworkService\AppData\Local\Temp' -Force
Start-Service MySQL80
Get-Service MySQL80
```

核对该账户具有临时目录访问权限；不要为解决这个问题向所有用户开放数据库数据目录。若先启动了控制台诊断实例，应正常关闭它再启动服务，避免争用同一数据目录和端口。MySQL官方[Windows服务说明](https://dev.mysql.com/doc/refman/8.0/en/windows-start-service.html)也要求服务账户可写入其临时目录。

服务恢复后若错误变为`Access denied for user 'root'@'localhost'`，说明已连接到MySQL，剩余问题是账户认证；单独核对本机`application.yml`或`MYSQL_PASSWORD`环境变量中的凭据。不要将密码发到聊天。端口恢复不等于已经通过认证或核对完数据库表。

2026-10-15本机恢复记录：经用户明确授权，先正常停库并离线备份数据目录和`my.ini`，再按[MySQL官方Windows密码恢复步骤](https://dev.mysql.com/doc/refman/8.0/en/resetting-permissions.html)将`root@localhost`密码同步为本机配置中的实际值。临时密码文件已删除，原服务配置保持不变，`MySQL80`恢复正常运行；后端同版本JDBC认证已通过。

当次检查发现`personal_health`原有17张表，随后仅按`sql/init_mysql_schema_safe.sql`补建缺少的`elder_risk_profile`、`elder_vital_measurement`、`personalized_risk_assessment`三张空表。补建后共20张表，表名和必需字段核对无缺项，原有业务记录保留。恢复前备份保存在MySQL安装数据配置目录下的`root-recovery-<UTC时间>/Data-backup/`，仅管理员和系统账户可访问。Navicat需使用同步后的密码重新连接；后端重启后读取当前本机配置。
