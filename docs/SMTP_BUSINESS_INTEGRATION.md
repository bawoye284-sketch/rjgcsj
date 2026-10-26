# QQ 邮箱业务联调

> 当前验收请使用 [H2 本地受控邮件联调](SMTP_H2_LOCAL_TEST.md)，无需 MySQL。本文保留为之前独立 MySQL 方案的记录，不再是默认操作步骤。当前家属健康邮件仅在连续异常阶段创建，单次 78→110 不会产生家属邮件。

本轮已接入 QQ 465 SSL/TLS，复用健康预警和 SOS 的现有持久化通知任务。没有发送验证码或业务邮件，也没有重新执行基础 SMTP 测试。独立工具成功收件的结果沿用用户确认。

## 隔离方式与保护

- 原 demo profile 或实际 H2 数据库仍强制 Mock，不能用开关打开真实外发。数据库不可用时也禁止真实外发。
- 因为保留了上述保护，真实业务链路需要非 H2 数据源。本项目已有 MySQL 驱动和 SQL，实现一个独立 MySQL 测试库，比新增数据库适配或放宽 H2 规则改动更小。只复用本机 MySQL 服务，不使用 `personal_health` 库。
- 新 profile 为 `smtp-integration`，数据库名称必须是 `ihms_smtp_test_` 开头的独立库；无数据库 URL、账号或密码回退。只监听本机 21290。
- `start_smtp_integration.ps1 -To` 只接受一个完整测试邮箱，并设置为唯一白名单。请填写本次已指定的 QQ 测试邮箱；本文不把私人邮箱地址固化在源码中。
- 所有真实邮件出口，包括邮箱验证码，都在 SMTP 适配器中再次校验白名单。空白名单、通配符、多个收件人拼接和邮件头注入均不能发送。
- 健康和 SOS 邮件仍需联系人当前授权、资料版本匹配及 `REAL` 验证；`DEMO` 不会自动变成真实验证。发送前再次检查，撤销授权或修改资料会阻止旧任务发送。
- 唯一键、最多三次已知失败重试、UNKNOWN 不自动重发、重启恢复均复用原任务机制。邮件故障不回滚测量、预测、预警或 SOS。
- 测试库初始化仅来自仓库的结构、虚构账号、关联关系和既有参考配置。没有复制当前 H2 数据、历史联系人、测量、预警或通知任务。没有新增业务数据库表或修改健康规则。

## 启动前准备

以下步骤会启动可真实发信的环境，**请在您明确决定开始真实邮箱验收后执行**。点击“验证邮箱”本身就会发送验证码邮件。此次开发没有代您执行这些步骤。

### 1. 打包独立后端

在项目根目录 PowerShell 执行：

```powershell
Set-Location D:\Projects\rjgcsj
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
& "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File .\scripts\build_smtp_integration.ps1 -Offline
```

产物 `IHMS-admin/target/personal-health-api-smtp-integration.jar`。该脚本使用临时 POM 指定独立文件名，结束后仅删除自己创建的临时 POM，不修改原 pom.xml，不替换正在运行的普通后端 JAR。`-Offline` 使用本机已有缓存；其他机器已配置 Maven 时可去掉该参数。需要 Java 17。

### 2. 创建全新 MySQL 测试库

先确认本机 MySQL 服务已启动且您知道其管理账号。生成 SQL 的命令只写本地文件，不连接数据库：

```powershell
& .\chronic-disease\.venv\Scripts\python.exe -B .\scripts\prepare_smtp_mysql.py --database ihms_smtp_test_20261026 --output .\IHMS-admin\target\smtp-business-init.sql
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' --host=127.0.0.1 --user=root -p --default-character-set=utf8mb4 --execute='source D:/Projects/rjgcsj/IHMS-admin/target/smtp-business-init.sql'
```

`-p` 在终端隐藏输入 MySQL 密码，不要把密码拼进命令。如管理账号不是 root，请修改 `--user`。生成文件使用独占创建，不覆盖已有文件；数据库使用 `CREATE DATABASE`，已有同名数据库会报错。不要加 `--force`，不要删除旧库；需要新一轮时改用新的测试库名和输出文件名。

可用数据库管理工具执行生成 SQL；不要执行带有 `USE personal_health` 的旧整库脚本。建议为这个测试库单独建立仅能访问该库的 MySQL 账号。

### 3. 启动独立后端

```powershell
$testTo = Read-Host '输入本次已指定的 QQ 收件测试邮箱'
& "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File .\scripts\start_smtp_integration.ps1 -To $testTo -DatabaseUrl 'jdbc:mysql://127.0.0.1:3306/ihms_smtp_test_20261026?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai'
```

脚本按需提示输入以下环境变量的值。授权码和数据库密码采用隐藏输入，只传递给子进程，不写文件、不放进 Java 参数；结束后还原原进程环境。

| 环境变量 | 内容 |
| --- | --- |
| `IHMS_SMTP_TEST_FROM` | 已验证可发信的 QQ 发件邮箱 |
| `IHMS_SMTP_TEST_AUTH_CODE` | 该邮箱的 SMTP 授权码，非登录密码 |
| `IHMS_SMTP_TEST_MYSQL_USERNAME` | 独立测试库的 MySQL 账号 |
| `IHMS_SMTP_TEST_MYSQL_PASSWORD` | 上述 MySQL 账号密码 |
| `IHMS_SMTP_TEST_MYSQL_URL` | 由 `-DatabaseUrl` 设置，无默认业务库 |
| `IHMS_SMTP_ALLOWED_RECIPIENTS` | 由 `-To` 覆盖为唯一测试邮箱 |

无需重新做基础 SMTP 测试。增加 `-Check` 可只检查入口参数和产物存在，不连接数据库、不发送邮件。数据库未初始化、密码不正确或连接错误时先修复这些问题，不要改用现有业务库。

现有模型服务如需联合评估，可继续在本机 8001 运行；此 profile 不自动启动或停止 Python 服务，模型版本沿用 `nhefs5-local-20261026`。仅测试 SOS 或测量保存接口时不依赖模型预测。

### 4. 启动单独前端

另开 PowerShell：

```powershell
Set-Location D:\Projects\rjgcsj\IHMS-view
$env:VUE_APP_API_BASE_URL = 'http://localhost:21290/api/personal-heath/v1.0'
$env:IHMS_VIEW_PORT = '21291'
npm.cmd run dev
```

打开 `http://localhost:21291`，不要使用原 21091 页面做本轮验收。浏览器 Network 中 API 必须指向 21290。前端默认地址和端口未改变；回到普通环境时使用新终端，或删除上述两个进程环境变量后重启前端。无需修改 `.env` 或本地 YAML。

## 浏览器验收：两种自动提醒

### 先完成真实邮箱验证及医生授权

1. 在独立前端用虚构账号 `demo_elder / 123456` 登录。
2. 个人中心 → 紧急联系人，新增一位测试联系人，邮箱只填本次允许的 QQ 测试邮箱，勾选通知授权并保存。
3. 点击“验证邮箱”，到 QQ 邮箱取验证码，回系统填写并确认。应显示“已授权 / 邮箱已验证”。这一步不手动改数据库验证状态，也不使用以前的演示验证码。
4. 开启已关联医生的预警授权。另一个浏览器窗口登录虚构医生 `demo_doctor / 123456`，打开“患者健康预警”。

### 收到一次健康异常提醒

在健康风险评估页面，使用虚构资料和正常其余测量，先成功提交心率 78 的测量，再成功提交心率 110 的新测量。此处只使用现有 60～100 范围，不修改阈值。110 应产生新的普通异常事件及一份联系人邮件任务。只点击“重新预测”不会新建测量，也不会触发邮件。

若暂不运行模型，可在上述已登录的测试页面浏览器控制台执行以下接口测试，它走同一个现有测量保存与规则检测链路，不依赖 AI；不会更改阈值：

```javascript
// 建议优先使用页面；如需接口方式，从 Network 中复制当前用户请求的 token。
// token 只在本机使用，不发给他人、不放进文档或仓库。
const testToken = prompt('当前测试用户 token');
async function saveMeasurement(heartRate) {
  const response = await fetch('http://localhost:21290/api/personal-heath/v1.0/personalized-risk/measurement', {
    method: 'POST', headers: { 'Content-Type': 'application/json', token: testToken },
    body: JSON.stringify({ heartRate })
  });
  if (!response.ok) throw new Error('测量提交未成功，请检查页面记录');
  return response.json();
}
await saveMeasurement(78);
await saveMeasurement(110);
```

等待后台任务处理（通常约 15 秒），通知记录应显示联系人邮件“已提交邮件服务器”，QQ 邮箱应收到标题“健康测量提醒”。医生端收到独立站内提醒，可查看和确认。SMTP 接受不代表已读；实际收件需以 QQ 邮箱为准。

不要连续提交多次异常来测试第一次收件，以免达到既有连续异常升级规则而产生额外的合法升级提醒。如果测试库中已有同一心率活动异常，先保存正常测量 78 使其恢复，再保存 110 形成新事件。

### 收到一次 SOS 求助提醒

1. 在 `/sos-help` 点击“发起紧急求助”并确认，无需新测量或 AI 结果。
2. 等待联系人邮件任务成为“已提交邮件服务器”，QQ 邮箱应收到“SOS主动紧急求助提醒”。
3. 医生端打开该患者紧急求助，点击“确认收到”，再按实际测试情况填写说明并“记录处理结果”。确认和处理状态分别保存。
4. 为只验收一次初始邮件，请及时确认该事件，避免既有超时规则产生后续提醒；本轮不改确认期限或 SOS 规则，也不必点击再次提醒。

页面刷新不应创建新事件或发出主动再次提醒。事件处理后仍保留历史；后续如主动发起新求助，按原流程创建独立事件。

## 失败及重试的核对

- 白名单不匹配：`SMTP_RECIPIENT_NOT_ALLOWED`，适配器不会连接 SMTP；不要扩大名单来解决错误收件地址。
- 授权码失败：`SMTP_AUTH_FAILED`；连接失败：`SMTP_CONNECT_FAILED`。保留发送尝试记录，已知失败最多三次，沿用原定时或手动重试入口。
- 超时/中断/结果不确定：`UNKNOWN` / `SMTP_RESULT_UNKNOWN`，不自动重发。先查看 QQ 收件箱，避免邮件已被接受后重复发送。
- 未授权、验证不符合要求或资料改变：任务跳过/取消，不伪装成发送成功。
- 正常业务日志不打印邮箱、验证码、授权码或邮件正文。不要开 JavaMail debug，也不要上传本地凭据或测试 SQL/数据库文件。
- 本轮没有要求您故意输入错误密码或重新测试基础 SMTP；失败路径已由自动化离线测试覆盖。

## 实际开发验证

| 验证 | 结果 |
| --- | --- |
| Maven 测试和独立可执行包 | 102 项通过，0 失败、0 错误、0 跳过，`target/smtp-final-package.log` |
| Python 工具/隔离 SQL 生成测试 | 19 项通过，`target/smtp-python-final.log` |
| Vue 生产构建（API 指向 21290） | 通过，已有两项包体积警告，`target/smtp-final-vue-build.log` |
| 启动脚本 `-Check` | 通过，不连接、不发送 |
| 真实数据库启动、生成 SQL 在 MySQL 执行 | 未执行成功；当前执行环境无 MySQL 凭据，空密码探测被拒绝，没有更改数据库 |
| 验证码、健康提醒、SOS 真实收件 | 按要求未发送，待您明确开始浏览器验收 |

102 项后端测试包含原健康规则、幂等、通知去重、重试、医生授权及 SOS 测试，并新增 QQ TLS 参数、白名单、验证邮件同样受限、测量保存不受白名单失败影响、H2/demo 禁止外发及隔离库守卫测试。SMTP 网络调用使用离线替身，不能当作真实业务收件证明。生成 SQL 通过结构检查，尚需在您本机的独立 MySQL 库执行验证。

本轮未重新执行六模型在线预测，不把替身模型的后端测试当作真实模型回归；没有修改模型算法、权重或原有版本配置。

## 本轮文件

- 后端：`HealthMailTransport.java`、新增 `SmtpIntegrationGuard.java`、新增 `application-smtp-integration.properties`、`health-notification.properties.example`。
- 测试：`HealthNotificationIntegrationTest.java`、新增 `HealthMailTransportTest.java`、新增 `SmtpIntegrationGuardTest.java`。
- 脚本：新增 `prepare_smtp_mysql.py`、`test_prepare_smtp_mysql.py`、`start_smtp_integration.ps1`、`build_smtp_integration.ps1`。
- 前端：`src/utils/request.js`（API 地址可配置）、`vue.config.js`（开发端口可配置）、`src/utils/healthAlertDisplay.js`（安全拒绝原因的友好文案）。没有修改 Vue 页面或业务请求格式。
- 文档：本说明。以前独立测试说明作为历史记录保留；现有适配器审查结论以本说明为准。

未更改本地 YAML、模型、健康阈值、SOS 规则或现有数据库，未执行 Git 暂存、提交、推送、合并或切换分支。GitHub 提交前排除 `target/`、H2 文件、训练产物、虚拟环境、本地配置和全部 SMTP/MySQL 凭据；保留用户原有未提交修改。
