# 测量规则预警：第二阶段交付

范围仅限测量规则预警。没有新增通知、联系人、SMTP、SOS 或医生授权体系。

## 行为

- `/personalized-risk/assess-current` 保留原模型调用、输入校验和 requestId 重放机制。模型成功后，测量、预测结果和待检测记录一起提交；随后在独立事务中检测。
- `/personalized-risk/measurement` 直接保存真实测量并创建检测记录，返回 `data.measurementId`。该旧入口没有 requestId，独立的重复 POST 仍表示新测量；同一 measurementId 的检测和重试不会重复创建事件。
- `/personalized-risk/assess` 只重新预测，不新增测量或检测。
- 每个用户通过 `user` 行的 `SELECT ... FOR UPDATE` 串行保存/处理测量；不使用“查最新一条”取得测量 ID。测量使用 JDBC 生成键，检测表的主键就是测量 ID。
- 检测按测量 ID 顺序进行。成功记录不重复检测；失败的前序记录阻止后序事件更新，避免乱序恢复。页面显示阻塞的测量 ID，并可重试。
- 自动任务每 30 秒扫描，每次失败等待 1 分钟，自动最多尝试 3 次；人工重试可重置失败任务。没有持续健康监测，只补偿已保存测量的检测。
- 每项指标最多保持一个活动事件（由用户行锁保护）；持续异常更新次数、最近原因和等级；有效实测恢复后关闭事件。缺测、规则禁用、未配置均不会关闭该指标的活动事件。
- 每次检测 JSON 保留规则、实际范围、实测值、原因、事件 ID、原等级与新等级；既有事件的最新摘要保存在事件表。历史测量不自动补检测。

## 三张新表

| 表 | 主要字段 |
|---|---|
| `health_alert_rule` | `metric_key` 主键、`config_id`、`enabled`、`consecutive_count`、`window_days`、`change_delta`、`baseline_count`、`revision` |
| `health_alert_evaluation` | `measurement_id` 主键、`user_id`、`status`、`attempts`、`result_json`、`error_message`、`retry_at`、`evaluated_at` |
| `health_alert_event` | `id`、`user_id`、`metric_key`、`level`、`status`、首次/最近测量 ID、`occurrences`、`details_json`、创建/更新/恢复时间 |

不修改原业务表结构。预测的测量 ID 同时放入原有 `input_snapshot_json` 和响应的 `measurementId`，历史旧记录仍可读取。

## 规则配置

管理员进入“指标配置”，展开“测量预警规则”。正常范围在原全局指标中编辑，格式为有限数字 `最小值,最大值`，支持小数。

| 稳定字段 | 默认全局 symbol（不区分大小写） |
|---|---|
| `systolic_pressure` | `SYS` |
| `diastolic_pressure` | `DIA` |
| `heart_rate` | `Bpm` |
| `spo2` | `SpO2` |
| `temperature` | `TEMP` |
| `respiratory_rate` | `RR` |

配置 ID 为空时，要求对应 symbol 恰好匹配一个全局指标；也可明确指定全局指标 ID。缺失、重复或删除映射会显示未配置。体温和呼吸频率没有新增默认范围，需管理员自行审核后配置。

默认连续异常为 7 天内最近 3 次该项有效测量均超出范围；缺测不补值，也不计入有效次数。近期变化默认关闭，填写正的绝对变化幅度后，与窗口内此前 N 次有效值均值比较，历史不足不触发变化提示。该提示不判断医学意义上的恶化。

等级为 `GENERAL`（普通异常或变化提示）、`REPEATED`（连续异常）。没有临床严重阈值，也没有可启用严重规则的入口。模型自身风险等级不受这些规则影响。

检测状态为 `PENDING`、`FAILED`、`COMPLETE`；完成结果为 `ALERT`、`INCOMPLETE`、`WITHIN_RULES`。未触发预警不等于所有健康指标正常。

## API

以下均省略 `/api/personal-heath/v1.0`。用户身份由 JWT 上下文取得，不接受替换 userId。

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/health-alerts/status?measurementId=...`（可省略 ID 查最近检测） | 本人用户/管理员自己的记录 |
| GET | `/health-alerts?page=1&size=10` | 本人历史事件 |
| POST | `/health-alerts/{measurementId}/retry` | 本人检测记录 |
| GET | `/health-alerts/rules` | 管理员 |
| PUT | `/health-alerts/rules/{metric}` | 管理员 |

规则更新体示例：

```json
{"configId":2,"enabled":true,"consecutiveCount":3,"windowDays":7,"changeDelta":null,"baselineCount":3}
```

普通用户只能修改自己的私有指标，不能更新/删除全局指标或他人的指标。管理员全局新增入口也保留原有角色检查。旧健康数据提醒改为支持小数范围，不改变其保存流程。

## 启动与手工验证

1. 使用已有 JDK 17 环境运行 `03_start_demo_backend.bat`；本地两个 YAML 无需改动。
2. 使用 `04_start_demo_frontend.bat`，或在 `IHMS-view` 执行 `npm.cmd run dev`。
3. 登录已有普通用户，在健康风险评估页“保存并联合预测”。测量成功保存后，下方“测量规则预警”独立显示本次测量 ID、原因和检测状态。
4. 以现有心率全局演示范围 `60,100` 为例，提交 78 不触发单项预警，提交 110 触发普通异常；连续 3 次有效异常更新同一个活动事件为连续异常。此处数字仅验证项目现有配置。
5. 其他项目留空会显示缺测；首次测量显示历史不足。展开历史记录查看事件次数与恢复状态。
6. 管理员可调整映射及连续/变化参数，普通用户不能调用规则更新接口。

H2：启动时自动执行增量建表，不删除旧数据。`demo/schema.sql` 由 `python scripts/build_demo_schema.py` 生成，修改新表时应维护 `sql/health_alert_upgrade_mysql.sql` 和生成器。MySQL 需先手工执行该升级 SQL；本次未连接真实 MySQL 运行验收。

## 可重复运行的检查

使用 JDK 17，在 `IHMS-admin` 执行 `mvn.cmd test` / `mvn.cmd package`；前端在 `IHMS-view` 执行 `npm.cmd run build`。

本机系统 Maven 配置存在旧镜像，首次离线测试缺少 JUnit 平台运行器；验收使用忽略目录下的独立构建设置及依赖缓存，不更改全局配置：

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
mvn.cmd -gs target/alert-build-settings.xml -s target/alert-build-settings.xml '-Dmaven.repo.local=.build-tools/repo' test
```

`target/alert-build-settings.xml` 内容为 `<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>`。上述路径均是本地验收产物，不提交。

Python 现有回归：

```powershell
cd risk-service
& ../chronic-disease/.venv/Scripts/python.exe -B -m unittest test_five_component test_online_assessment
```

HTTP 六模型验收脚本 `scripts/verify_measurement_alerts.py` 会写入演示测量及预测，**只应对独立临时 H2 库运行**。启动独立后端示例（在 IHMS-admin 内）：

```powershell
& 'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/java.exe' -jar target/personal-health-api-1.0-SNAPSHOT.jar --spring.profiles.active=demo --server.port=21190 '--spring.datasource.url=jdbc:h2:file:./target/health-alert-smoke/personal_health;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=USER,VALUE;DB_CLOSE_ON_EXIT=FALSE'
```

随后在项目根目录执行 `python -B scripts/verify_measurement_alerts.py`。脚本按当前本地版本 `nhefs5-local-20261026` 核验，运行并行个性化/五病种评估、请求重放、不新增测量的重评估、独立测量预警。不要将 base-url 指向需要保留数据的服务。

## 验收记录（2026-10-26）

- 后端集成测试：25 项全部通过，无失败、无跳过，包含正常、单项、连续、变化、缺测、历史不足、失败隔离/重试、前序阻塞、幂等、并发、权限、旧 H2 升级及原有重评估行为。
- Python 原有 10 项测试全部通过。
- 真实六模型 HTTP 验收通过，五种慢性病均返回 predicted，实际版本为 nhefs5-local-20261026。
- H2 独立文件库正常启动；原用户 H2 文件正被现有进程占用，未复制成功，未对原文件进行升级验收。旧结构迁移另由独立 H2 测试覆盖。
- 前端生产构建成功，有资源体积警告；开发服务器启动成功，HTTP 200。
- 浏览器视觉验收未执行：当前没有连接可用浏览器。真实 MySQL 验收未执行。

没有切换分支、commit、push 或 merge。用户原有模型数据/报告修改和工作期间出现的 `.vscode/settings.json` 修改均保留，不属于此次交付。

## 实际文件清单

修改的 11 个既有文件：

```text
IHMS-admin/pom.xml
IHMS-admin/src/main/java/com/star/controller/HealthModelConfigController.java
IHMS-admin/src/main/java/com/star/controller/PersonalizedRiskController.java
IHMS-admin/src/main/java/com/star/model/dto/risk/PersonalizedRiskResponse.java
IHMS-admin/src/main/java/com/star/service/impl/HealthModelConfigServiceImpl.java
IHMS-admin/src/main/java/com/star/service/impl/UserHealthServiceImpl.java
IHMS-admin/src/main/java/com/star/service/risk/PersonalizedRiskService.java
IHMS-admin/src/main/resources/demo/schema.sql
IHMS-view/src/views/admin/HealthModelConfigManage.vue
IHMS-view/src/views/user/HealthAssessment.vue
scripts/build_demo_schema.py
```

新增的 10 个文件：

```text
IHMS-admin/src/main/java/com/star/config/HealthAlertSchedulingConfig.java
IHMS-admin/src/main/java/com/star/controller/MeasurementAlertController.java
IHMS-admin/src/main/java/com/star/service/health/MeasurementAlertService.java
IHMS-admin/src/test/java/com/star/MeasurementAlertIntegrationTest.java
IHMS-view/src/api/healthAlerts.js
IHMS-view/src/components/risk/MeasurementAlertRules.vue
IHMS-view/src/components/risk/MeasurementAlerts.vue
sql/health_alert_upgrade_mysql.sql
scripts/verify_measurement_alerts.py
docs/HEALTH_ALERT_PHASE2.md
```

建议手工仅暂存以上 21 个文件，不使用 `git add .` 混入已有模型报告或 IDE 修改。可使用提交标题 `feat: add persisted measurement rule alerts`；本次未执行任何暂存或提交。
