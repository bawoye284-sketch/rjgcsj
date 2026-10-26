# 通知业务展示清理

本轮只调整通知查询、返回视图、前端状态展示及相关测试。没有删除数据库记录、执行数据库升级、修改健康规则/模型/SOS幂等或提醒策略，没有修改YAML、模型文件、训练报告、独立SMTP测试工具或其他原有本地改动。分支保持 `feat/wearable-health-alert`，未执行Git暂存、提交、推送、合并或切换。

## 实际修改文件

前端：

- `IHMS-view/src/components/risk/HealthNotifications.vue`
- `IHMS-view/src/views/user/SosHelp.vue`
- `IHMS-view/src/components/risk/EmergencyContactSettings.vue`
- `IHMS-view/src/components/risk/MeasurementAlertRules.vue`
- `IHMS-view/src/views/user/HealthAssessment.vue`
- `IHMS-view/src/utils/healthAlertDisplay.js`

后端：

- `IHMS-admin/src/main/java/com/star/service/notification/HealthMailTransport.java`
- `IHMS-admin/src/main/java/com/star/service/notification/HealthNotificationService.java`
- `IHMS-admin/src/main/java/com/star/service/notification/SosService.java`
- `IHMS-admin/src/main/java/com/star/controller/SosController.java`

测试和验收脚本：

- `IHMS-admin/src/test/java/com/star/HealthNotificationIntegrationTest.java`
- `IHMS-admin/src/test/java/com/star/SosIntegrationTest.java`
- `scripts/verify_health_notifications.py`
- `scripts/verify_notification_capture_recovery.py`
- `scripts/verify_sos.py`
- `scripts/verify_sos_reminders.py`
- 本文档。

医生预警页面复用公共通知组件和状态工具，未重复修改其列表、授权或处理逻辑。测量预警页面已检查，本轮无需额外修改其代码。

## 清理结果

- 删除通知记录和SOS页面的“演示通知”标记及相关确认弹窗文字，移除公共工具中的“演示通知已完成”。
- 删除紧急联系人中的演示验证说明。DEMO验证在用户页面显示“未完成邮箱验证”，不能冒充真实邮箱验证；只有REAL显示“邮箱已验证”。
- 邮件服务不可用时，验证操作提示“未配置邮件服务，暂时无法验证邮箱”，不再向用户填入或展示模拟验证码。内部验证码测试能力和数据库验证类型保留。
- 删除评估页的模拟手环说明，保留现有实测表单及未测项留空提示；未增加真实硬件能力。
- 管理员规则标题去掉“项目演示配置”。扫描整个 `IHMS-view/src` 后，“演示/模拟/Mock”自然语言只剩两处不渲染的原有代码注释，没有相关用户可见展示。内部MOCK枚举仍用于安全分支。
- SOS保留“如遇紧急情况，请立即联系当地急救服务。”；不自动拨号。

## 通知状态对照

| 实际后端字段 | 用户展示 |
| --- | --- |
| IN_APP + PENDING | 等待发送 |
| IN_APP + SENDING | 发送中 |
| IN_APP + SENT，read_at为空 | 已发送 |
| IN_APP + SENT，read_at非空 | 已读 |
| EMAIL + PENDING，邮件渠道已配置可用 | 等待发送 |
| EMAIL + PENDING，当前邮件渠道不可用 | 未配置邮件服务 |
| EMAIL + SENDING | 发送中 |
| EMAIL + SENT | 已提交邮件服务器 |
| FAILED | 发送失败 |
| SMTP_NOT_CONFIGURED | 未配置邮件服务 |
| UNKNOWN | 发送结果尚不明确 |
| CANCELLED | 已取消后续提醒 |
| SKIPPED | 暂未发送；备注保留验证或授权原因 |
| MOCK_SENT | 业务列表不显示、不计入业务列表总数；防御性回退为“未实际发送”，不映射成功 |
| 医生OPEN / ACKNOWLEDGED / RESOLVED | 待确认 / 已确认收到 / 已处理 |

邮件SENT只表示SMTP提交成功，不表示到达收件箱或用户已读。医生响应依据医生操作/事件状态，与通知是否发送、站内是否标为已读分别展示。

## 查询、统计和记录保留

`/health-notifications`在SQL层排除MOCK_SENT，列表和total使用同一过滤条件，避免仅在前端隐藏导致空页或总数不准确。真实邮件SENT历史保留，即使当前邮件服务不可用，也不改写过去的实际状态。

SOS创建、详情、当前求助及再次提醒接口使用业务返回视图，排除MOCK_SENT，并返回 `emailServiceEnabled`。内部原始详情、任务、发送日志仍保留，供测试与数据库诊断使用。SosService的创建、重放、频率限制、确认、处理及升级逻辑不变。

未执行的邮件任务仍保留。当H2/demo保护生效或邮件配置缺失时，未发送邮件显示“未配置邮件服务”，不能显示“已发送”。这里指当前环境没有可用的真实邮件渠道，不披露内部环境名称。SOS汇总只依据可见的真实通知：例如“站内消息已发送；未配置邮件服务”，不把隐藏记录解释为家属已收到。

未新增或删除数据表，未修改任何初始化或升级脚本。没有清除已有预警、测量、预测、SOS或医生处理记录。

## SMTP保护

保留demo/H2强制禁止真实外发、真实邮箱验证、授权和资料版本检查。现有业务SMTP仍为原来的STARTTLS实现；QQ465独立测试工具未改动，也没有自动启用业务SMTP或真实邮件联调。

修复一处可能造成假成功的保护分支：适配器发现外发被禁用时不再静默返回，而是返回SMTP_NOT_CONFIGURED失败，防止调用者把一次未发送操作记为SENT。正常Mock内部发送仍由原任务机制记录为MOCK_SENT，用户查询将其排除。邮件失败和健康数据保存仍隔离。

## 实际验证

后端在IHMS-admin执行：

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
& D:/Java/apache-maven-3.9.4/bin/mvn.cmd -gs target/alert-build-settings.xml -s target/alert-build-settings.xml -o '-Dmaven.repo.local=.build-tools/repo' test
```

**85项通过，0失败，0错误，0跳过。** 使用独立内存H2；包含测量正常/异常/连续/恢复、医生权限与ID隔离、站内已读、Mock记录隐藏与分页总数、原始任务和测量保留、真实邮件历史保留、外发保护、SOS首次创建/再次提醒/去重/限流/确认/处理/重启恢复等回归。

在IHMS-view执行 `npm.cmd run build`：**成功，无编译错误，有2个资源体积警告**。

另执行37项前端核对：**全部通过**，包括站内已发送/已读、邮件提交/未配置/失败/未知、Mock回退不冒充成功、联系人真实验证区分、SOS汇总、7个组件模板编译及相关禁用文案扫描。没有后端请求。

日志位于忽略目录：

- `IHMS-admin/target/notification-business-view-test.log`
- `IHMS-admin/target/notification-business-view-build.log`
- `IHMS-admin/target/notification-business-view-check.log`

四个HTTP验收脚本已同步新接口约定：模拟记录处理完成后从业务返回结果中消失，按可见站内消息核对，不再把Mock计成邮件成功。脚本语法检查通过；**本轮没有再次执行这些独立进程HTTP验收脚本**。其底层行为由本轮85项后端测试覆盖。

本轮未执行真实QQ邮件发送、完整SMTP业务联调、浏览器点击或视觉验收；没有启动/停止用户后端，没有操作当前演示库。YAML与先前保护哈希核对一致。

## 浏览器验收

1. 完全停止旧后端后，用现有 `03_start_demo_backend.bat` 启动；重启或刷新现有前端（必要时在IHMS-view运行 `npm.cmd run serve`）。需同时加载新后端查询过滤和新前端，不能只刷新旧后端上的页面。
2. 老人个人中心 → 健康通知记录：不出现演示标记；历史Mock邮件不在列表或分页总数中。真实站内消息显示等待发送/已发送，点击“标为已读”后刷新显示已读。
3. 紧急联系人：原有授权不变；之前DEMO验证显示“未完成邮箱验证”，不是邮箱已验证。在当前受保护环境点击验证邮箱，显示未配置邮件服务，不提供模拟验证码填写流程。
4. 健康评估页：实测表单、风险预测及异常历史正常，未出现模拟手环说明；异常及恢复规则不变。
5. SOS页面：仍可首次发起、查看进展、在间隔允许后再次提醒。刷新不会自动发送。Mock邮件不出现在通知列表中；当前环境只能实际产生站内消息时，汇总明确区分站内发送和邮件服务未配置。
6. 医生端 → 患者预警：原授权范围不变，医生站内消息可查看和标为已读；确认收到、处理结果及原始备注仍分别保存。处理SOS后老人页面显示已处理，可发起新事件。
7. 查询历史预警、SOS及医生操作记录，确认历史未被删除。Mock原始记录可在开发测试库/诊断查询中核对，不通过普通业务页面展示。

当前H2/demo不会发送真实邮件。后续真实QQ联调仍需独立非demo、非H2环境、指定测试邮箱白名单、真实验证与授权，不能以业务界面清理为由解除外发保护。
