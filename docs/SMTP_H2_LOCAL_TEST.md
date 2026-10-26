# 原 H2 项目的受控 QQ 邮件演示

无需 MySQL，不用重新测试基础 SMTP。使用原 H2 数据库、原 21090 后端、21091 前端和原模型配置。

本轮未启动真实发信服务，未发送验证码或业务邮件。下面命令留待您明确开始收件验收后执行。

## 已修正的邮件策略

| 操作/结果 | 家属邮件 |
| --- | --- |
| 正常测量 | 不创建 |
| 第一次有效异常 | 不创建 |
| 第二次连续有效异常 | 不创建 |
| 第三次连续有效异常，后端进入 REPEATED | 创建一份提醒任务 |
| 同一事件继续异常、重复请求、任务重复扫描 | 不重复创建同阶段提醒 |
| SOS 主动求助 | 立即创建紧急邮件任务，不依赖测量或 AI |

沿用管理员现有连续异常规则（当前配置为 3 次），没有改指标范围、算法或连续异常判定。家属邮件现在仅消费 REPEATED 阶段；GENERAL 的单次超范围或变化提示不发家属邮件。用户和授权医生的站内通知仍按原有规则工作。正常 78 再异常 110 只有一次有效异常，绝不是三次。

## 开启方式

先在项目根目录打包最新代码（不启动服务、不发送邮件）：

```powershell
Set-Location D:\Projects\rjgcsj
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
& "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File .\scripts\build_smtp_integration.ps1 -Offline
```

复用之前的独立产物构建工具，生成 `IHMS-admin/target/personal-health-api-smtp-integration.jar`；这个文件名不要求 MySQL。原 pom.xml 和普通后端包不被替换。

决定开始真实收件验收后，先正常关闭原后端启动窗口，避免两个进程同时占用 H2 文件或 21090 端口。不要删除 H2 文件、不要强制解除数据库锁，也不需要清空任何数据。然后执行：

```powershell
$testTo = Read-Host '输入此前指定的 QQ 测试收件邮箱'
& "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File .\scripts\start_local_mail_test.ps1 -To $testTo -UserAccount demo_elder -EnableRealMail
```

`-UserAccount` 是您要操作的现有普通用户登录账号，不是医生账号或数据库 ID。如果不是 `demo_elder`，请改为实际账号；账号不存在或不是普通用户时启动失败，不会扩大到所有用户。

脚本会询问 QQ 发件邮箱和 SMTP 授权码。授权码隐藏输入，只进入本次子进程环境，不写源码、YAML、日志或命令参数。若本地已设置下列环境变量，则直接复用：

| 变量 | 内容 |
| --- | --- |
| `IHMS_SMTP_TEST_FROM` | 已测试成功的 QQ 发件邮箱 |
| `IHMS_SMTP_TEST_AUTH_CODE` | 发件邮箱 SMTP 授权码，非登录密码 |

脚本自行设置本次进程的 `IHMS_LOCAL_MAIL_ENABLED=true`、`IHMS_LOCAL_MAIL_MODE=SMTP`、`IHMS_LOCAL_MAIL_USER`、`IHMS_LOCAL_MAIL_TO`，激活 `demo,local-mail-test`，并在结束后恢复环境。不使用 MySQL 环境变量。缺少 `-EnableRealMail` 拒绝启动；`-Check` 仅检查参数，不启动进程、不连接数据库、不发送邮件。

前端继续使用原启动方式和 `http://localhost:21091`。如之前使用过独立 21290 环境，请在新终端启动前端，或先清除旧的进程变量：

```powershell
Set-Location D:\Projects\rjgcsj\IHMS-view
Remove-Item Env:VUE_APP_API_BASE_URL -ErrorAction SilentlyContinue
Remove-Item Env:IHMS_VIEW_PORT -ErrorAction SilentlyContinue
npm.cmd run dev
```

## 浏览器操作

1. 登录 `-UserAccount` 指定账号，在个人中心设置指定的 QQ 测试联系人并授权。请确保该账号下只有一条已授权联系人使用这个测试邮箱；重复地址会拒绝验证和发送，避免同一邮箱收多封。
2. 点击“验证邮箱”，取 QQ 邮箱收到的真实验证码，在页面输入并确认，直到显示“邮箱已验证”。原来的演示验证不能替代这一步；验证邮件本身也只允许发到本次指定的唯一邮箱。
3. 开启关联医生的预警授权，方便同时验收站内提醒及确认处理。
4. 在健康风险评估页面使用虚构健康资料，其他指标填写正常值。先成功保存一次心率 **78**，再逐次成功保存 **110、111、112**。正常值用于使之前的心率异常恢复；之后才是本次三个连续有效异常。不要使用只重新预测、不保存测量的入口。
5. 前两次异常没有家属邮件，第三次应出现一份邮件任务；通常等待后台一个扫描周期（约 15 秒），状态应为“已提交邮件服务器”，QQ 邮箱应收到“健康测量提醒”。继续一次 113 不应重复发送同一事件提醒。
6. 打开紧急求助页，主动发起并确认 SOS。应立即创建邮件任务，经后台发送后 QQ 邮箱收到“SOS主动紧急求助提醒”。医生可在患者预警页确认收到、记录处理结果。为只验收首次 SOS 邮件，请及时确认，避免原有超时提醒规则触发后续提醒。

若存在本次启动之前尚未结束的 SOS，请先由关联授权医生按实际情况处理结束，再发起新的求助。本模式不会把旧 SOS 的再次提醒或升级变成真实外发。没有改 SOS 的去重、升级、再次提醒或确认期限。

“已提交邮件服务器”只表示 SMTP 接受，不等于收件人已读；实际收件以 QQ 邮箱为准。健康评估仍使用现有本地模型服务，不替换、不重训任何模型。

## 如何阻止旧通知和其他联系人外发

- 默认只启动 demo 仍是 Mock；仅设置 SMTP 参数也不能使 H2 外发。必须同时启用额外 profile 和显式开关。
- 启动时记录当前测量、事件、任务的最大 ID。真实业务邮件必须属于本次启动后的新事件、新任务和新测量（SOS 不要求测量）。延迟捕获旧事件也不能通过，旧 Mock 记录不会重新排队或变为真实发送。
- 仅指定账号和指定邮箱参与受控邮件；真实验证码也受同样限制。授权、REAL 验证、资料版本检查仍在后端执行，其他历史联系人不会被发送。
- 未通过本次范围检查的历史邮件任务不领取、不消耗发送次数，原记录不删除。查询先过滤旧任务再分页，旧待办多时也不会阻塞本次新通知。
- 新任务仍使用原任务表、唯一键、发送日志和最多三次已知失败重试；不确定结果 UNKNOWN 不自动重发。测量、预测、预警及 SOS 不会因发信失败回滚。
- **每次重启都是一个新的受控会话**：上次进程的未完成邮件不会自动真实重发；原记录保留。这是本地防历史补发措施。本次会话内的失败重试正常保留。需要重演时，新启动后先保存正常值，再提交三次异常；SOS 先处理旧事件后再发起。

测试结束后正常关闭该窗口，再运行原 `03_start_demo_backend.bat` 即恢复默认 Mock。无需改回 YAML。

## 文件与实际验证

本轮修改：

- `HealthMailTransport.java`：受控开关、唯一账号/邮箱范围、启动边界和默认 Mock 保护。
- `HealthNotificationService.java`：家属邮件仅连续异常阶段创建、受控收件人筛选及历史任务隔离；SOS 仍复用原通知任务。
- `EmergencyContactService.java`：真实验证码也检查本次受控账号/联系人范围。
- 新增 `application-local-mail-test.properties`、`scripts/start_local_mail_test.ps1`，不修改原 YAML。
- 新增 `LocalH2MailIntegrationTest.java`，调整 `HealthNotificationIntegrationTest.java` 及 `verify_health_notifications.py` 的首次异常预期。
- 更新配置示例和原 MySQL 文档的入口提示，以本文为当前演示步骤。

实际执行：

| 命令/检查 | 结果 |
| --- | --- |
| `scripts/build_smtp_integration.ps1 -Offline` | Maven 测试、独立打包通过；最终日志见 `IHMS-admin/target/h2-local-mail-package.log` |
| Python `unittest discover -s scripts -p 'test_*smtp*.py'` | 19 项通过，0 失败 |
| `start_local_mail_test.ps1 ... -Check` | 通过，未启动、未连接、未发送 |

新增测试使用独立内存 H2，不打开现有文件库；真实适配器、验证码、授权和任务链路均参与，仅最终网络发送器被离线替身替换。覆盖前三次异常、正常恢复与缺测、持续异常去重、唯一收件人、SOS 独立立即任务、旧任务/延迟捕获隔离、默认关闭、真实验证、撤销授权及有限重试。

没有对现有 H2 数据库执行联调操作，没有实际收件验收，也没有重新运行六模型在线预测。本轮没有修改 Vue 页面、模型或健康阈值，没有 Git 暂存、提交、推送、合并或切换分支。
