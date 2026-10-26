# 第三阶段：家属邮件通知与医生健康预警管理

交付范围：复用第二阶段测量规则与事件，新增紧急联系人、邮箱验证、医生预警授权、持久化通知、医生确认/处理。没有家属账号、SOS、持续监测或真实短信。

## 实现与数据边界

测量、AI 结果、检测结果与事件的原保存逻辑保留。检测事务提交后，独立事务从 `health_alert_evaluation.result_json` 生成通知任务。生成异常会记录安全错误码 `CAPTURE_FAILED`（数据库可用时），原检测结果仍是可恢复的数据源。发送只由独立通知流程执行，不能回滚测量、预测或事件。

通知记录明确区分 `USER`、`DOCTOR`、`CONTACT`；医生 ID 来自登录 JWT 的医生上下文，不使用普通用户上下文或前端传入的医生 ID。本人可看自己事件的任务日志；医生只能看发给自己的、仍具有患者授权的医生通知。

医生预警权限同时要求：医生接诊状态有效、该医患存在 `status=0` 的进行中咨询、老人明确打开预警授权。列表、详情、确认、处理和通知读取都在后端检查。咨询结束、医生停诊或授权撤销后，预警查询/处理权限失效。现有其他健康页面保留原权限流程。

确认与处理记录独立于异常的 `ACTIVE/RECOVERED` 状态。医生处理完成不代表测量指标恢复。每个等级阶段分别确认；从普通异常升级到连续异常后，该阶段重新显示待确认。详细页展示最近 100 次相关测量及处理记录。

## 数据库与升级

不修改既有表字段，新增 7 张表：

| 表 | 主要字段/约束 |
|---|---|
| `health_emergency_contact` | 本人 user_id、姓名、关系、邮箱、手机号、授权、邮箱/手机验证状态、资料 revision、时间 |
| `health_contact_verification` | contact_id 主键、SHA-256 令牌摘要、DEMO/REAL、资料版本、过期时间、错误尝试次数 |
| `health_doctor_authorization` | `(user_id,doctor_id)` 主键、授权、更新时间 |
| `health_notification_checkpoint` | measurement_id 主键、CAPTURED/FAILED、生成错误码及次数、时间 |
| `health_notification_task` | 本人、事件、测量、接收者类型/ID/资料版本、渠道、等级阶段、状态、次数、错误码、重试/租约/发送/已读时间 |
| `health_notification_attempt` | `(task_id,attempt_no)` 主键，每次发送开始/结束、状态和安全错误码 |
| `health_alert_action` | 事件、医生、等级阶段、ACKNOWLEDGED/RESOLVED、备注、时间 |

任务唯一约束：`(event_id,recipient_type,recipient_id,channel,phase)`。

H2：使用现有启动 SQL 自动创建新表，不清空数据。生成文件 `demo/schema.sql` 由 `python scripts/build_demo_schema.py` 更新。

MySQL：在第二阶段升级完成后，执行 `sql/health_notification_upgrade_mysql.sql`。脚本只新增表，可重复执行；本次没有连接真实 MySQL 验收。

## 联系人与验证

用户“个人中心”内新增联系人与预警授权设置。联系人须填写姓名、关系、邮箱，可填写手机号，并明确选择通知授权。每名用户最多 10 名联系人。手机号只记录，不发送短信。

验证标记只能由后端产生：

- `NONE`：未验证；不能接收真实或模拟的健康邮件。
- `DEMO`：Mock 验证；一次性演示码仅返回本人界面，不能成为真实邮箱验证。
- `REAL`：在允许真实 SMTP 的环境，将验证码发到联系人邮箱；验证申请响应不返回真实验证码。邮箱持有人将收到的验证码交由本人在系统内确认。

验证码 15 分钟有效，单次最多 5 次错误尝试，申请间隔至少 1 分钟，只存摘要，成功后删除。修改邮箱/手机号会清除对应验证标记；修改联系人资料会递增版本并作废尚未使用的验证请求。旧版本待发送邮件会取消。

请先设置授权和验证，再提交新异常测量。已因缺少验证而 `SKIPPED` 的任务不会在验证后自动补发，新增联系人也不主动补发已完成生成的历史阶段。

## 通知与重试

- 本人和授权医生使用站内通知；授权家属联系人使用邮件。
- 正常或资料不足且无异常事件的测量不创建异常通知。
- 活动事件首次产生通知；普通异常持续时不重复创建。默认在升级到 `REPEATED` 时新增一次升级通知；可关闭升级通知策略。
- 任务创建与检查点在同一独立事务内提交，患者行锁与唯一约束防止并发重复创建。
- 后台每 15 秒扫描未生成任务的成功检测和待发送/明确失败任务，重启后继续处理。
- 发送通过任务行锁领取，状态变为 `SENDING`、次数加一，设置 2 分钟租约；完成和每次尝试均持久化。
- 明确连接/认证失败为 `FAILED`，自动按尝试次数等待 1/2 分钟重试，总计最多 3 次；本人可提前重试未达上限的明确失败任务。
- 超时、可能部分投递或其他不确定发送为 `UNKNOWN`，不自动或手工盲目重发。进程中断后过期的发送租约也进入 `UNKNOWN`。
- `MOCK_SENT` 是模拟成功；`SKIPPED` 表示未满足当前模式的验证要求；`CANCELLED` 表示联系人版本/授权或医生权限失效。

真实发送在通知专用事务中锁定联系人，重新检查资料版本、授权和真实验证；联系人编辑/撤销与该最终检查按顺序执行。不会持有测量事务或患者行锁进行网络发送。已交给 SMTP 的邮件无法撤回；SMTP 接收成功也不保证最终进入收件箱。

健康邮件只提醒“有新的测量规则预警，请联系本人并登录查看”，不发送完整档案、具体测量值或处理备注。日志仅保存错误码；新接口的请求对象与响应日志不会输出联系方式、验证码或私人备注。JavaMail debug 关闭。

## API

省略 `/api/personal-heath/v1.0`：

| 方法 | 接口 | 权限/用途 |
|---|---|---|
| GET/POST | `/emergency-contacts` | 本人列表/新增 |
| PUT/DELETE | `/emergency-contacts/{id}` | 本人编辑/删除 |
| POST | `/emergency-contacts/{id}/verification` | 本人申请邮箱验证 |
| POST | `/emergency-contacts/{id}/verification/confirm` | `{ "token": "一次性码" }` |
| GET | `/health-doctor-authorizations` | 本人当前有效咨询关联医生及授权 |
| PUT | `/health-doctor-authorizations/{doctorId}` | 本人设置 `{ "authorized": true/false }` |
| GET | `/health-notifications?page=1&size=10` | 本人任务日志/医生本人通知，含生成失败和尝试记录 |
| POST | `/health-notifications/{id}/retry` | 本人重试有确定失败结果且未达上限的任务 |
| PUT | `/health-notifications/{id}/read` | 当前接收者标记自己的站内通知已读 |
| GET | `/doctor-health-alerts` | 医生授权患者列表；page/size/level/state/handling/from/to 筛选 |
| GET | `/doctor-health-alerts/{id}` | 医生查看详情、测量历史、处理历史 |
| POST | `/doctor-health-alerts/{id}/acknowledge` | 医生确认，`{ "note": "备注" }` |
| POST | `/doctor-health-alerts/{id}/resolve` | 医生处理，`{ "note": "备注" }` |

level 为 GENERAL/REPEATED；state 为 ACTIVE/RECOVERED；handling 为 OPEN/ACKNOWLEDGED/RESOLVED；from/to 为 YYYY-MM-DD。备注须为 1～1000 字。前端传入 userId/doctorId 不会改变当前身份。

## Mock 与 SMTP 配置

默认无需修改本地 YAML：Mock、后台处理和升级通知均默认启用。

`demo` profile **或实际 H2 数据源** 都强制 Mock，即使 mode=SMTP 且 smtp.enabled=true 也不能真实发送。Mock 分支不调用 SMTP 发送适配器。

真实邮件同时要求：非 demo、非 H2、显式 SMTP 模式、显式 enabled、合法 SMTP 配置；健康提醒接收联系人还必须授权且邮箱验证为 REAL。演示联系人验证不因切换模式自动升级。

提供 `IHMS-admin/src/main/resources/health-notification.properties.example` 作为说明文件，**不会自动加载**。可在非演示环境通过进程环境变量配置，无需覆盖已有 YAML：

```powershell
$env:HEALTH_NOTIFICATION_MODE='SMTP'
$env:HEALTH_NOTIFICATION_SMTP_ENABLED='true'
$env:HEALTH_NOTIFICATION_SMTP_HOST='<SMTP服务器>'
$env:HEALTH_NOTIFICATION_SMTP_PORT='587'
$env:HEALTH_NOTIFICATION_SMTP_FROM='<发件邮箱>'
$env:HEALTH_NOTIFICATION_SMTP_USERNAME='<SMTP用户名>'
# HEALTH_NOTIFICATION_SMTP_PASSWORD 通过本机私密环境配置设置，不写入脚本或仓库
```

使用现有 MySQL profile 启动；本实现要求 STARTTLS 和证书主机名校验，默认 587，连接/读/写超时各 5 秒，不提供关闭 TLS 校验的配置。其他配置键：`health-notification.worker-enabled`、`health-notification.notify-upgrades`（均默认 true）。

## 本地完整演示

1. 使用原有 JDK 17 环境运行 `03_start_demo_backend.bat`、`04_start_demo_frontend.bat`，无需修改 application.yml/application-demo.yml。
2. 用 `demo_elder / 123456` 登录，打开个人中心，新增授权联系人（可使用 example.invalid 测试邮箱）。
3. 点“验证邮箱”，界面自动填入 Mock 演示码，点“确认验证”；状态为演示验证。
4. 在关联医生授权列表打开李医生授权；没有医生时，先通过原有医生咨询功能创建进行中的咨询。
5. 在健康风险评估页提交保存并联合预测。按照现有心率演示范围 60,100，110 可触发普通异常；连续三次有效异常升级为连续异常。没有修改这些范围。
6. 等待约 15 秒，在个人中心“健康通知记录”刷新：本人/医生站内通知为 SENT，联系人邮件为 MOCK_SENT。展开可看失败原因和每次尝试。
7. 用 `demo_doctor / 123456` 登录医生工作台，点击“患者健康预警”。筛选并查看事件，填写备注后确认或处理。
8. 老人撤销授权或结束咨询后，该医生无法再查看、确认或处理这些预警。

建议验收使用独立 H2 文件库，不向原演示库批量写测试数据。

## 实际执行的检查（2026-10-26）

后端（在 IHMS-admin，使用已有 JDK 17）：

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
mvn.cmd -gs target/alert-build-settings.xml -s target/alert-build-settings.xml -o '-Dmaven.repo.local=.build-tools/repo' test
mvn.cmd -gs target/alert-build-settings.xml -s target/alert-build-settings.xml -o '-Dmaven.repo.local=.build-tools/repo' package
```

本机缓存最初缺少 Spring 邮件依赖，已从公开 Maven 仓库下载到项目内忽略目录；没有修改全局 Maven 配置。上述独立 settings 为第二阶段已有本地构建产物。其他配置正常的机器可直接运行 `mvn.cmd test`、`mvn.cmd package`。

结果：51 项集成测试全部通过（第二阶段 25 项＋第三阶段 26 项），0 失败、0 跳过；打包成功。覆盖正常不通知、异常持久化、持续去重、升级策略、联系人验证绕过、权限和同 ID 隔离、发送/生成失败隔离、有限重试、不确定结果、并发、租约恢复、旧 H2 升级及第二阶段全部回归。

```powershell
cd IHMS-view
npm.cmd run build
```

Vue 构建通过，仅有资源体积警告。开发服务器在独立端口启动并返回 HTTP 200。

```powershell
cd risk-service
& ../chronic-disease/.venv/Scripts/python.exe -B -m unittest test_five_component test_online_assessment
```

现有 Python 10 项测试全部通过。真实 HTTP 六模型回归：项目根目录执行 `python -B scripts/verify_measurement_alerts.py`，目标为独立 H2 的 21190 后端；个性化和五病种结果均保存，五病种均为 predicted，版本保持 `nhefs5-local-20261026`。

真实进程重启验收：在独立 `target/notification-final-smoke/personal_health` H2 库启动 21190 后端，先传 `--health-notification.worker-enabled=false`，执行：

```powershell
python -B scripts/verify_health_notifications.py prepare --state IHMS-admin/target/notification-final-smoke/state.json
```

成功生成 3 条 PENDING 任务后停止本次临时进程，再对同一 H2 文件启动后端并启用 worker，执行：

```powershell
python -B scripts/verify_health_notifications.py check --state IHMS-admin/target/notification-final-smoke/state.json
```

通过：重启后恢复 3 条任务、连续异常无重复、升级新增 3 条任务、医生确认/处理成功、撤销后列表无权访问。即使启动参数显式设置 SMTP 模式及 enabled=true，实际仍为 Mock，无真实外发。

这些 HTTP 脚本会写演示数据，只应对独立临时库运行。日志/结果位于 `IHMS-admin/target/notification-*.log` 与 `target/notification-final-smoke/`，均不提交。

## 未执行与已知限制

- 未向真实 SMTP 服务或真实联系人发送邮件；真实发送的资格检查、成功/失败/不确定结果通过受控发送替身测试，H2 禁止外发另有本地监听测试。
- 未连接真实 MySQL 验收，需在实际 MySQL 环境执行增量 SQL 后复核。
- 未完成浏览器视觉验收：当前没有已连接的浏览器。Vue 构建和启动检查已执行。
- 原 H2 演示文件没有用于批量验收；测试使用独立内存/文件数据库，旧 H2 结构增量升级由回归测试覆盖。
- 不保证 SMTP 端到端“恰好一次”或最终收件；不确定结果保留 UNKNOWN，避免盲目重发。已提交发送的邮件不能撤回。

## 实际文件清单

本阶段修改 8 个已有文件（其中包含第二阶段尚未提交文件）：

```text
IHMS-admin/pom.xml
IHMS-admin/src/main/java/com/star/service/health/MeasurementAlertService.java
IHMS-admin/src/main/resources/demo/schema.sql
IHMS-admin/src/test/java/com/star/MeasurementAlertIntegrationTest.java
IHMS-view/src/router/index.js
IHMS-view/src/views/doctor/DoctorHome.vue
IHMS-view/src/views/user/Main.vue
scripts/build_demo_schema.py
```

本阶段新增 15 个文件：

```text
IHMS-admin/src/main/java/com/star/controller/DoctorHealthAlertController.java
IHMS-admin/src/main/java/com/star/controller/HealthCareController.java
IHMS-admin/src/main/java/com/star/service/notification/DoctorAlertService.java
IHMS-admin/src/main/java/com/star/service/notification/EmergencyContactService.java
IHMS-admin/src/main/java/com/star/service/notification/HealthMailTransport.java
IHMS-admin/src/main/java/com/star/service/notification/HealthNotificationService.java
IHMS-admin/src/main/resources/health-notification.properties.example
IHMS-admin/src/test/java/com/star/HealthNotificationIntegrationTest.java
IHMS-view/src/api/healthCare.js
IHMS-view/src/components/risk/EmergencyContactSettings.vue
IHMS-view/src/components/risk/HealthNotifications.vue
IHMS-view/src/views/doctor/DoctorHealthAlerts.vue
scripts/verify_health_notifications.py
sql/health_notification_upgrade_mysql.sql
docs/HEALTH_ALERT_PHASE3.md
```

## GitHub 提交前

仍在 `feat/wearable-health-alert`；未暂存、commit、push、merge 或切换分支。第二阶段文件是本阶段依赖，检查后一起手工暂存。不要直接 `git add .` 混入以下内容：

- 用户原有 chronic-disease/data/nhefs/download_manifest.json、reports/nhefs/cohort_audit.json 和本地训练报告目录。
- 用户的 `.vscode/settings.json` 修改。
- application.yml、application-demo.yml、任何 SMTP 账号密码或验证令牌。
- 模型权重、训练数据、Python 虚拟环境、node_modules、target、dist、.build-tools、logs、demo-data 数据库及验收状态文件。

可使用提交标题 `feat: add authorized health alert notifications and doctor handling`。第三阶段完成即停止，未实现 SOS。
