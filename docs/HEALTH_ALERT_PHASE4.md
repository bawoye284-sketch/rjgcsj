# 第四阶段：SOS 紧急求助与响应升级

## 范围与前置复核

本次先复核第三阶段，再完成第四阶段。没有重写第二、三阶段，也没有开始第五阶段整体联调或提交 Git。

第三阶段复核：51 项测试重新通过，Maven 打包、Vue 构建、原 Python 10 项回归、六模型 HTTP 评估、真实进程重启恢复 3 条通知任务均通过。此前中断发生在验收后的清理环节。

SOS 是本人主动发起的独立流程，不调用 AI、不提交测量、不新增临床阈值。系统没有持续监测，也不能发现无人操作时的突发急症。电子通知不能替代实际急救响应。

## 复用与新增结构

- 复用 `health_alert_event` 的全局事件 ID，SOS 使用 `metric_key='SOS'`、`level='EMERGENCY'`。测量 ID 留空，不伪造测量数据。
- 复用第三阶段的 `health_notification_task/attempt`、真实验证/Mock 保护、联系人授权、医生授权和角色隔离。
- 复用 `health_alert_action` 及医生详情、确认、处理接口。SOS 动作仍由当前登录医生执行，并要求有效咨询关系、接诊状态和老人预警授权。
- 测量规则历史明确排除 SOS；原 `/assess-current`、`/measurement`、`/assess` 的测量和 requestId 流程不变。

仅新增两张表：

| 表 | 字段 |
|---|---|
| `health_sos_event` | event_id 主键、OPEN/ACKNOWLEDGED/RESOLVED、确认期限、下次升级检查时间、升级阶段、已生成通知阶段、生成错误码、确认/处理时间 |
| `health_sos_request` | `(user_id,request_id)` 主键、event_id、请求创建时间 |

现有表增量调整：

- `health_alert_event.first_measurement_id/last_measurement_id` 允许 NULL，原记录的值保留。
- `health_notification_task.measurement_id` 允许 NULL。
- `health_notification_task.priority` 新增，默认 0；原通知保持 0，SOS 为 100＋升级阶段。
- 原事件表的 SOS 状态为 ACTIVE，处理后为 CLOSED；测量事件原有 ACTIVE/RECOVERED 含义不变。

H2 启动时通过生成后的 `demo/schema.sql` 自动增量升级。MySQL 在前两阶段升级完成后执行 `sql/health_sos_upgrade_mysql.sql`：先放宽测量 ID 的非空约束，再按 information_schema 检查添加 priority，最后新增两张表。不会删除数据、重建数据库或覆盖旧任务。

生成方式仍为 `python scripts/build_demo_schema.py`，保留原初始化逻辑。独立测试已验证第三阶段旧结构中的测量、事件、通知任务在重复升级后保留。

## 幂等、状态与升级

SOS POST 必须携带 UUID requestId；后端规范化大小写后按本人账号查重。同一请求始终返回同一事件，包括该事件已处理的情况。新请求在已有未结束 SOS 时复用该活动事件。

创建、请求映射、确认和升级共用患者行锁；不依赖进程内锁，因此多线程/多实例也不会为同一用户重复创建活动事件。

事件状态：

1. OPEN：事件已创建，等待医生确认。
2. ACKNOWLEDGED：授权医生已确认，停止后续升级；尚未处理完成。
3. RESOLVED：授权医生处理完成，关闭事件。取消尚未发送的 SOS 提醒，清除无需再重试的生成标记。新的 UUID 才可发起下一次求助。

默认初始通知：本人站内＋所有当前授权关联医生站内＋按创建顺序排列的首位符合授权/验证要求的联系人邮件。默认确认期限 120 秒；无人确认时，每 120 秒最多升级一次，默认最多两次，每次通知一名尚未通知过的符合条件联系人，并向本人/授权医生发升级站内通知。

联系人不足时仍保存升级阶段及站内任务，不编造其他接收者。默认策略最多覆盖前三位符合条件联系人；可通过下述批量人数配置调整。

通知阶段为 `SOS_INITIAL`、`SOS_ESC_1`、`SOS_ESC_2` 等，继续使用第三阶段唯一约束防止重复。状态与期限存入数据库，重启后每 5 秒检查一次。阶段先持久化，再由独立事务生成任务；失败保留 `SOS_CAPTURE_FAILED` 和未生成阶段供重试。

通知发送仍由第三阶段 worker 每 15 秒处理，高优先级任务优先领取。生成/邮件发送失败均不回滚 SOS；发送明确失败最多尝试 3 次，结果不确定保留 UNKNOWN，不盲目重发。医生确认后，待发送的升级任务取消；正在向 SMTP 提交的邮件无法撤回。

## 接口

以下均省略 `/api/personal-heath/v1.0`：

| 方法 | 接口 | 说明 |
|---|---|---|
| POST | `/sos` | 本人创建/复用求助，体为 `{ "requestId": "UUID" }` |
| GET | `/sos/current` | 本人最近一次求助及状态 |
| GET | `/sos/{id}` | 本人详情、各阶段通知与医生记录 |
| GET | `/sos?page=1&size=10` | 本人历史求助 |
| GET | `/sos/policy` | 确认期限、升级策略、演示标记、急救号码 |

上述入口只允许本人普通用户账号，医生不能因数字 ID 相同而成为该用户。userId 不由前端指定。

复用并扩展：

- `GET /doctor-health-alerts?level=EMERGENCY` 查询授权患者 SOS；详情返回 `kind=SOS` 和 SOS 状态。
- `POST /doctor-health-alerts/{id}/acknowledge` 确认 SOS，仍需填写 note。
- `POST /doctor-health-alerts/{id}/resolve` 处理 SOS，仍需填写 note。
- `GET /health-notifications` 继续查询任务及尝试日志，SOS 任务包含 priority 和阶段。

每次查询、确认、处理都重新检查医生授权及有效关联。原测量等级 GENERAL/REPEATED 不改变。

## 页面与完整演示

1. 使用原有 JDK 17 环境运行 `03_start_demo_backend.bat` 和 `04_start_demo_frontend.bat`。本地两个 YAML 无需修改，H2 保留旧数据并自动加表。
2. 用普通用户登录，在个人中心设置联系人、通知授权和邮箱验证，并给有效咨询关联的医生授权。演示验证只能接收 Mock 通知。
3. 用户顶栏有明显的“SOS 紧急求助”按钮，进入 `/sos-help`。点击“发起紧急求助”并确认，不用填测量或等待预测。
4. 页面分别显示事件已创建、通知待生成/发送、模拟发送成功、医生已确认、事件已处理；每 5 秒刷新的是求助状态，不是生理监测数据。
5. 同一用户有未结束 SOS 时按钮禁用；后端仍执行活动事件去重。网络失败时保留当前请求号供重试。可查看历史求助，查看历史时不会被刷新切回最新事件。
6. H2/demo 页面只显示号码和“演示不拨号”按钮，点击仅提示，不唤起拨号。非演示部署提供用户主动点击的 tel 链接；服务器不拨打电话。
7. 医生登录工作台进入“患者健康预警”，选择“SOS 紧急求助”等级查看，填写备注后确认或处理。SOS 详情不展示伪造测量历史。
8. 无人确认时，按配置的期限和间隔升级到其他联系人；医生确认后停止升级，处理后关闭事件并取消迟到的排队提醒。

必须主动联系当地急救或身边人员，不能把“通知已发送”理解成有人接听、救护车已派出或急救已完成。没有授权医生时，页面明确提示缺少可通知对象；事件不会自动假定有人处理。

## 配置与 Mock

新增 `health-sos.properties.example` 只作说明，不自动加载、不含密钥。可使用进程参数或环境变量，不覆盖本地 YAML。

| 配置 | 默认值/限制 |
|---|---|
| health-sos.confirmation-seconds | 120，最少 10 秒 |
| health-sos.escalation-seconds | 120，最少 10 秒 |
| health-sos.max-escalations | 2，范围 0～5 |
| health-sos.initial-contact-count | 1，范围 1～10 |
| health-sos.escalation-contact-count | 1，范围 1～10 |
| health-sos.emergency-number | 中国演示示例 120，部署时确认当地号码 |
| health-sos.worker-enabled | true |

H2 或 demo 仍强制 Mock，即使显式设置 SMTP mode/enabled 也不会外发。真实环境继续要求非 demo、非 H2、显式启用 SMTP、联系人授权及 REAL 邮箱验证。SOS 邮件只含简短主动求助提醒，不发送完整档案或实测数据。短信、自动拨号和真实急救平台均未接入。

## 实际测试结果（2026-10-26）

最终 Maven：74 项全部通过，0 失败、0 错误、0 跳过。其中第二阶段 25 项、第三阶段 26 项、SOS 23 项。

覆盖：主动独立求助、相同/不同请求活动事件复用、大小写 UUID、并发去重、已结束请求重放、初始/升级任务、配置上限、确认/处理停止升级、迟到任务取消、联系人撤权、无验证不选择、医生权限和同 ID 隔离、生成/发送失败隔离、重启实例恢复、状态 API、旧第三阶段数据库重复升级及原二/三阶段回归。

实际执行（IHMS-admin）：

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
mvn.cmd -gs target/alert-build-settings.xml -s target/alert-build-settings.xml -o '-Dmaven.repo.local=.build-tools/repo' test
mvn.cmd -gs target/alert-build-settings.xml -s target/alert-build-settings.xml -o '-Dmaven.repo.local=.build-tools/repo' package
```

打包通过；上述 settings/缓存是已有忽略目录下的本机构建配置，未改动全局环境。普通 Maven 配置正常时直接运行 test/package 即可。

前端：`npm.cmd run build` 通过，有资源体积警告；`npm.cmd run dev -- --port 21291 --host 127.0.0.1` 启动成功，HTTP 200。

Python：在 risk-service 执行 `../chronic-disease/.venv/Scripts/python.exe -B -m unittest test_five_component test_online_assessment`，10 项通过。

六模型：在独立 H2 后端执行 `scripts/verify_measurement_alerts.py --base-url http://127.0.0.1:21290/api/personal-heath/v1.0`，个性化与五病种预测均保存，五病种全为 predicted，模型版本仍为 nhefs5-local-20261026；重复 requestId 复用原测量，/assess 不新增测量。

真实进程重启验收使用独立文件库 `target/sos-final-smoke/personal_health`，没有操作原演示数据库。启动示例（IHMS-admin）：

```powershell
& 'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/java.exe' -jar target/personal-health-api-1.0-SNAPSHOT.jar --spring.profiles.active=demo --server.port=21290 --health-notification.worker-enabled=false --health-sos.worker-enabled=false --health-sos.confirmation-seconds=10 --health-sos.escalation-seconds=30 '--spring.datasource.url=jdbc:h2:file:./target/sos-final-smoke/personal_health;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=USER,VALUE;DB_CLOSE_ON_EXIT=FALSE'
```

在项目根目录运行：

```powershell
python -B scripts/verify_sos.py prepare --state IHMS-admin/target/sos-final-smoke/state.json
```

准备阶段通过：事件创建、3 条高优先级待发送任务、无新增测量、重复请求复用、演示不拨号策略。随后停止该临时进程，保持同一数据库并把两个 worker-enabled 改为 true 后重启，再运行：

```powershell
python -B scripts/verify_sos.py check --state IHMS-admin/target/sos-final-smoke/state.json
```

验收已通过：重启恢复 3 条初始任务、期限升级新增 3 条任务、Mock 成功、医生 2 无权读取用户 2 的 SOS、医生 1 确认后再等待 32 秒仍不继续升级、医生处理成功、已结束请求仍返回原事件。测试启动还显式配置 SMTP mode/enabled，实际保持 Mock。

证据：`target/sos-package.log`、`target/sos-frontend-build.log`、`target/sos-final-smoke/*.json`、Surefire 报告。脚本会创建演示数据，只对独立临时库运行，不要指向需要保留数据的服务。

## 未执行与限制

- 未执行真实 SMTP 外发、真实 MySQL 验收或真实拨号。SMTP 资格、失败与不确定结果由受控替身测试，H2 禁止外发有第三阶段本地监听测试覆盖。
- 当前没有已连接浏览器，未做浏览器视觉或交互端到端验收。已完成 Vue 构建、启动及后端状态/权限 API 验证，不把它们表述为视觉验收通过。
- 原演示 H2 没有用来写入验收数据；旧结构升级在独立数据库验证。
- SOS 需要后端及网络可用；无授权医生时不会自动获得医疗响应，事件需授权医生处理后关闭。
- 联系人按创建顺序分批选择，没有新增家属账号、联系人独立端、真实短信或急救派单。
- SMTP 交付不保证最终收件；已提交的发送不能撤回，不确定状态不盲目重发。

## 本阶段实际文件清单

修改 9 个既有文件（包含前两阶段尚未提交代码）：

```text
scripts/build_demo_schema.py
IHMS-admin/src/main/resources/demo/schema.sql
IHMS-admin/src/main/java/com/star/service/health/MeasurementAlertService.java
IHMS-admin/src/main/java/com/star/service/notification/HealthNotificationService.java
IHMS-admin/src/main/java/com/star/service/notification/DoctorAlertService.java
IHMS-view/src/views/user/Main.vue
IHMS-view/src/router/index.js
IHMS-view/src/components/risk/HealthNotifications.vue
IHMS-view/src/views/doctor/DoctorHealthAlerts.vue
```

新增 9 个文件：

```text
sql/health_sos_upgrade_mysql.sql
IHMS-admin/src/main/java/com/star/service/notification/SosService.java
IHMS-admin/src/main/java/com/star/controller/SosController.java
IHMS-admin/src/test/java/com/star/SosIntegrationTest.java
IHMS-admin/src/main/resources/health-sos.properties.example
IHMS-view/src/api/sos.js
IHMS-view/src/views/user/SosHelp.vue
scripts/verify_sos.py
docs/HEALTH_ALERT_PHASE4.md
```

## 本地保护与提交前排除

仍在 feat/wearable-health-alert。没有执行 git add/commit/push、切换/创建/合并分支、reset 或 clean。本地 application.yml、application-demo.yml、IDE 设置及原有数据报告已按本轮开始时的哈希核对，未改变；Python 模型、权重、训练数据、虚拟环境和六模型逻辑未修改。

手工检查后仅暂存功能代码、升级脚本、测试和文档，并保留二/三阶段依赖。排除 application*.yml、本地 SMTP 配置/密钥、模型权重和训练数据、chronic-disease 本地报告改动、.vscode/settings.json、虚拟环境、node_modules、target、dist、.build-tools、logs、demo-data、测试 H2 文件和状态文件。

可使用提交标题 `feat: add idempotent SOS help and authorized escalation`。第四阶段完成即停止，不自动开始第五阶段整体联调。
