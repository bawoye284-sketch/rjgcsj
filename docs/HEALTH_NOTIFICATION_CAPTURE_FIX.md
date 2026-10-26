# 第三阶段通知任务生成故障修复（2026-10-26）

## 实际根因

实际 H2 文件的 `health_notification_task` 缺少 `priority` 列，但运行代码的任务插入和待发送查询均使用它。`personal_health.trace.db` 记录了 H2 `42122 / Column "priority" not found`，其中明确包含通知任务 INSERT 和后台 ORDER BY priority 查询。

因此任务生成事务整体回滚，另一个事务写入 `CAPTURE_FAILED` 检查点。检测结果和预警已先行提交，未丢失。此前异常被捕获但没有记录底层错误，导致页面只能看到通用失败提示。不是前端隐藏记录，也不是联系人 DEMO 验证或医生授权筛选错误。

实际库备份查询：27 条测量、10 条预测记录、1 个预警事件；6 条 COMPLETE 检测结果，6 个 FAILED/CAPTURE_FAILED 检查点，0 个通知任务。存在 1 个已授权且 DEMO 验证的联系人和 1 个已开启的医生授权。

优先级列此前只在后续阶段升级段声明，第三阶段任务代码却已依赖它。代码、初始化资源和运行数据库没有同步升级；仅重新编译正在运行的后端不能保证数据库初始化脚本重新执行。

## 本次修改

- `IHMS-admin/src/main/java/com/star/config/HealthNotificationSchema.java`：数据库初始化完成后检查通知表；若缺少 priority，执行可用于 H2/MySQL 的 `ALTER TABLE ... ADD COLUMN priority INT NOT NULL DEFAULT 0`。只补列、不重建表；失败阻止不兼容服务启动。
- `IHMS-admin/src/main/java/com/star/service/notification/HealthNotificationService.java`：依赖上述初始化完成；任务生成、检查点和后台处理失败记录异常类型、SQLState、厂商错误码，不记录异常正文、参数或联系方式。
- `sql/health_notification_upgrade_mysql.sql`：第三阶段新建表直接包含 priority。
- `scripts/build_demo_schema.py` 与生成的 `IHMS-admin/src/main/resources/demo/schema.sql`：在第三阶段执行幂等补列，不依赖后续阶段升级段。
- `IHMS-admin/src/test/java/com/star/HealthNotificationIntegrationTest.java`：新增缺列复现及 6 个检查点恢复测试。
- `scripts/verify_notification_capture_recovery.py`：只读 HTTP 恢复检查，默认连接独立测试服务；支持按事件过滤，避免浏览器同时新增其他事件干扰验收。
- `scripts/verify_measurement_alerts.py`：正常输入改为 110/70，适配已有演示范围 90..119、60..79；不改变规则配置。
- 本文档。

无新增接口，无新增业务表。保留现有唯一键 `(event_id,recipient_type,recipient_id,channel,phase)`、用户行锁、检查点事务和发送日志；同一事件初次及连续异常升级各通知一次。明确发送失败仍最多尝试 3 次，发送结果不确定仍不自动重发。未更改本地 YAML、模型、阈值、测量保存或 requestId 机制，未开发新的 SOS 功能。

## 实际验证

所有本次产物在忽略目录 `IHMS-admin/target/notification-repair/`；其中的数据库备份、日志不能提交。

1. 独立内存 H2 复现：删除 priority 后提交 6 次异常测量，全部测量/检测保存，6 个检查点失败，任务为零；执行补列两次并重新捕获/发送两次，只有 6 个任务及 6 个发送记录。联系人 Mock 发送且 SMTP 适配器从未调用。
2. 实际库停止占用进程后备份为 `baseline.mv.db`，复制到 `recovery.mv.db`；在副本上启动服务（关闭通知 worker），HTTP 验证 6 个待处理结果、0 个任务；重启同一副本并开启 worker，自动恢复成功。
3. 副本恢复结果：6 个 CAPTURED 检查点、0 个失败检查点；用户站内 SENT 2 条、医生站内 SENT 2 条、联系人 MOCK_SENT 2 条；每条任务只有一次发送记录。再次定时运行无重复。医生登录查询只返回医生自己的两条通知。
4. 副本恢复前后，测量表、预测记录表和预警事件表的全行 SHA-256 一致（分别 27/10/1 条）。这是任务恢复本身的数据保留验证，随后六模型测试新增的数据仅写入副本。
5. 实际 21090 服务只读 HTTP 复验原事件 #1：6 条任务恢复、无待捕获和失败检查点、家属模拟发送成功、医生可查询、重复调度无重复。实际服务已有其他浏览器活动，复验按原事件过滤，不把其他事件当作重复通知。
6. Maven `package`：**75 项通过，0 失败，0 错误，0 跳过**（第二阶段 25、第三阶段 27、既有 SOS 回归 23）。`package.log`；专项通知测试 27 项通过，`tests.log`。
7. Vue `npm.cmd run build`：成功，2 个资源体积警告；`frontend.log`。
8. Python：在 `risk-service` 目录运行 `../chronic-disease/.venv/Scripts/python.exe -B -m unittest test_five_component test_online_assessment`，**10 项通过**，`python.log`。
9. 独立副本 HTTP 六模型联评：个人风险及五种慢病全部实际预测成功，版本 `nhefs5-local-20261026`，requestId 重放和 `/assess` 不新增测量检查通过；`six-model-recheck.json`。正常测量为 INCOMPLETE（未配置体温/呼吸规则），并未错误声称所有指标正常。

首次 Python 命令在错误工作目录执行，出现两个模块导入错误，已在正确目录重跑通过。首次 HTTP 回归使用 120/80，被现有管理员规则正确判为异常；更正测试输入后通过。首次实际服务重启尝试遇到文件锁：当时已有另一个 21090 后端启动成功，未继续终止它，改为检查其实际接口并通过。首次实际库去重检查将浏览器新增的其他事件也计入，按原事件过滤后通过。原始失败日志保留，未隐藏。

本次未执行真实 SMTP 外发（demo 强制 Mock）、真实 MySQL 实例测试或浏览器视觉点击测试。后端启动、持久化恢复、页面使用的查询接口已实际验证。MySQL 补列采用通用语法，仍建议部署该环境前备份并独立验收。

## 复现命令

后端使用 Java 17。本机离线构建命令（工作目录 IHMS-admin）：

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
& D:/Java/apache-maven-3.9.4/bin/mvn.cmd -gs target/alert-build-settings.xml -s target/alert-build-settings.xml -o '-Dmaven.repo.local=.build-tools/repo' package
```

前端：在 IHMS-view 运行 `npm.cmd run build`。

独立库恢复验收（不能使用生产库做缺列复现）：先以相同副本启动 21190 后端，设置 `--health-notification.worker-enabled=false`，执行：

```powershell
chronic-disease/.venv/Scripts/python.exe -B scripts/verify_notification_capture_recovery.py pending
```

停止该测试后端，再用相同数据库且 worker 启用重启，执行：

```powershell
chronic-disease/.venv/Scripts/python.exe -B scripts/verify_notification_capture_recovery.py recovered
```

该命令专门验收本次六个待处理检测的固定样本，不能直接用于任意新空库。SQL 升级不需要删除检查点，也不需要再次提交旧测量。

## 浏览器复验

当前演示后端 21090 的原事件已恢复；不要重复启动占用同一 H2 文件的后端。若以后重启，完全停止该后端后运行项目 `03_start_demo_backend.bat`（Java 17）；前端使用原启动方式或在 IHMS-view 运行 `npm.cmd run serve`。

1. 老人原账号登录 → 个人中心 → 健康通知记录 → 点击“刷新”。原来的 6 个待处理提示和 CAPTURE_FAILED 提示应消失。
2. 原事件可看到普通异常和连续异常两个阶段：本人站内、医生站内、家属邮件各一条；家属为“模拟发送成功”，模式 MOCK。若已新增其他事件，总数可超过 6。
3. 展开任务，发送日志每条一次；等待至少 15 秒再刷新，同一事件同一阶段不增加记录。
4. 关联医生登录 → 患者健康预警/通知记录，查看原患者的两阶段通知及异常详情。未授权医生仍受原后端校验限制。
5. 在六模型评估页面提交新的演示数据，检查测量、AI 结果、规则预警及通知记录；只有新事件或允许的等级升级创建通知。连续同等级异常不会重复通知。

Git 提交前只选择本次源码/文档；排除 `target/`、`demo-data/`、`logs/`、本地 application YAML、模型/训练数据、虚拟环境、邮箱密钥及其他原有本地修改。未执行 Git 暂存、提交、推送、合并或分支切换。
