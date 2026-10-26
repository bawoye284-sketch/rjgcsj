# QQ SMTP 独立邮件测试

本轮新增独立工具，不修改业务适配器、健康规则、阈值、AI模型、SOS、数据库、本地YAML或既有未提交修改。不会继续开发心率突变检测。

## 现有业务邮件审查

- `HealthMailTransport.mockMode()`：demo profile、实际H2数据库、未明确启用SMTP，任一条件均使业务邮件进入模拟模式；数据库无法确认时也回退模拟模式。没有去除或绕过该保护。
- 现有业务适配器使用STARTTLS，默认端口587，校验证书与主机名，关闭邮件调试。不能把端口直接改为465而继续沿用STARTTLS配置；465隐式TLS由本次独立工具单独实现。
- 联系人验证码仅存哈希，有有效期和尝试次数限制。演示验证码只产生DEMO验证，不升级为REAL；真实验证码由邮件发送并完成验证。
- 健康邮件最终发送前检查当前授权、联系人资料版本和邮箱验证；真实发送要求REAL。医生站内通知复用当前关联及授权校验。
- 通知任务与发送尝试持久化，唯一键防止同事件/接收人/渠道/阶段重复。已知失败最多尝试3次；超时或工作进程中断等不确定结果记录UNKNOWN，不盲目重复发送。测量和预警保存与邮件发送隔离。
- 业务适配器目前没有测试收件白名单，本轮也未启用业务SMTP。后续联调前需要为所有真实出口增加测试白名单，覆盖联系人验证码与健康通知；不能只限制健康通知而放开验证邮件。

## 新增文件

| 文件 | 用途 |
| --- | --- |
| `scripts/smtp_test.py` | Python标准库独立SMTP测试，不连接数据库，不读取系统联系人 |
| `scripts/test_qq_smtp.ps1` | 本地PowerShell入口，隐藏输入授权码，临时传入子进程环境变量 |
| `scripts/test_smtp_test.py` | 16项离线协议及隐私测试，模拟SMTP，不真实外发 |
| `docs/SMTP_LOCAL_TEST.md` | 本说明 |

## 开通QQ邮箱SMTP

在QQ邮箱网页端进入“设置 → 账号与安全 → 安全设置”，开启第三方POP3/IMAP/SMTP服务并生成授权码。此处需要的是SMTP授权码，不是QQ登录密码。官方说明：[如何生成授权码](https://help.mail.qq.com/detail/106/985)。

工具固定连接 `smtp.qq.com:465`，使用隐式SSL/TLS、TLS1.2或以上并校验证书；不提供关闭证书校验或明文降级开关。服务器参数参考[腾讯云邮箱配置说明](https://intl.cloud.tencent.com/zh/document/product/1266/71700)，实现使用[Python SMTP_SSL](https://docs.python.org/3/library/smtplib.html)。

## 需要填写的参数

| 名称 | 内容 |
| --- | --- |
| `IHMS_SMTP_TEST_FROM` | 完整发件QQ邮箱，例如您自己的QQ或Foxmail账号 |
| `IHMS_SMTP_TEST_AUTH_CODE` | 上述发件账号生成的SMTP授权码 |
| `-To` / Python的 `--to` | 本次明确指定的一个收件测试邮箱，建议使用自己可登录查看的QQ邮箱 |

没有默认收件人，不接受逗号分隔的多地址，不读取历史联系人，不使用业务 `IHMS_SMTP_*` 配置。授权码没有命令行参数，不写入文件，也不打印原始SMTP响应。

## 推荐PowerShell命令：发送一封

```powershell
Set-Location D:\Projects\rjgcsj
$testTo = Read-Host '请输入本次收件测试邮箱（完整地址）'
& "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File .\scripts\test_qq_smtp.ps1 -To $testTo
```

随后按提示输入：

1. `Sender QQ email address`：发件QQ邮箱完整地址。
2. `QQ SMTP authorization code`：授权码，输入不回显；不要输入QQ登录密码。

执行策略参数仅作用于这次新建的PowerShell进程，不修改系统永久策略。脚本优先使用项目现有 `chronic-disease/.venv/Scripts/python.exe`，找不到时使用PATH中的Python（建议3.9或以上），无需安装依赖。

脚本将输入临时放入进程环境变量，供Python读取，结束后恢复原环境变量并释放安全输入缓冲区。若此前已经设置这两个变量，脚本直接使用它们，不再询问；不读取或生成 `.env` 文件。不要把授权码写进PowerShell历史、项目配置或聊天消息。

只校验、不发送：在同一命令末尾增加 `-Check`。它仍需填写配置，但不会连接QQ服务器。

若已通过本地安全方式准备好环境变量，也可以直接运行：

```powershell
.\chronic-disease\.venv\Scripts\python.exe -B -X utf8 .\scripts\smtp_test.py --to $testTo --check
.\chronic-disease\.venv\Scripts\python.exe -B -X utf8 .\scripts\smtp_test.py --to $testTo
```

第二条才真实发送。每次执行最多提交一封，不自动重试；无需启动Java、Vue、H2或模型服务。

## 输出和收件确认

邮件标题固定为 **健康有道 - 邮件功能测试**。正文为纯文本，仅含测试说明及随机测试编号，不包含健康档案、测量或SOS信息。

| 输出结果 | 含义 |
| --- | --- |
| CONFIG_VALID，退出码0 | 配置格式有效；未连接、未发送 |
| SMTP_ACCEPTED，smtp_code=250，退出码0 | 实际观察到服务器接受DATA；尚不能证明邮箱收到或用户阅读 |
| NOT_ACCEPTED，退出码1 | 未获得服务器接受结果；根据脱敏阶段和状态码排查 |
| CONFIG_INVALID，退出码2 | 配置缺失、格式错误或地址无效，未连接 |
| UNKNOWN，退出码3 | 提交期间断开/超时等结果不确定；先查邮箱，勿立即重复发送 |

输出收发地址仅保留掩码，并提供随机 `test_id`、`message_id` 便于定位。原始服务器回复和异常文本不打印，避免泄露授权码或完整地址。QUIT清理失败不会把已观察到的DATA 250误判成未发送。

登录指定的收件QQ邮箱，检查收件箱、垃圾邮件及其他分类，搜索完整标题，打开邮件核对正文测试编号与控制台 `test_id`。如只收到SMTP_ACCEPTED但没找到邮件，应检查过滤规则、退信及QQ邮箱自助查询，不要把“SMTP接受”当成“已收到”。

## 常见问题

| 错误/阶段 | 排查方式 |
| --- | --- |
| AUTH_FAILED / 535 | 确认第三方SMTP已开启；使用当前发件账号的有效授权码，检查复制空格；修改账号密码后旧授权码可能过期，重新生成 |
| DNS_FAILED | 检查DNS及网络能否解析smtp.qq.com |
| CONNECTION_FAILED / CONNECT | 检查本机、代理、防火墙及网络是否允许外连465；不会切换明文端口 |
| TLS_CERTIFICATE_FAILED | 检查系统时间、证书和代理拦截；不要关闭证书校验 |
| SENDER_REJECTED | 确认发件地址就是登录SMTP的QQ账号 |
| RECIPIENT_REJECTED / 550 | 检查测试收件地址是否正确、邮箱是否可用或受限 |
| MESSAGE_REJECTED / 550或554 | 可能是发信限流、反垃圾或账号限制；查看QQ邮箱自助查询/退信记录，避免连续重复发送 |
| UNKNOWN / DATA | 邮件可能已经被服务器接受；先搜索标题及测试编号，确认后再决定是否重新运行 |
| 脚本运行被执行策略拦截 | 使用上面的完整PowerShell命令；不需要永久修改系统执行策略 |

## 本轮实际验证与停止点

执行：

```powershell
.\chronic-disease\.venv\Scripts\python.exe -B -m unittest discover -s scripts -p test_smtp_test.py -v
```

**16项通过，0失败，0跳过**。覆盖单收件人/注入校验、缺失配置、证书校验、465隐式TLS、中文纯文本、认证/收件/正文拒绝、DATA超时未知、QUIT失败后的接受状态，以及错误中隐藏凭据。日志位于忽略目录 `IHMS-admin/target/smtp-test-unit.log`。

PowerShell入口以虚构配置和 `-Check` 实际运行成功，输出 `CONFIG_VALID`、`connected=false`、`sent=false`，收发地址均已脱敏。首次直接执行被本机脚本执行策略拦截，采用上述仅限单个进程的命令后通过。

当前会话没有配置测试发件邮箱或授权码，也没有用户指定的真实测试收件邮箱，**未执行真实SMTP外发，尚无QQ收件成功结论**。本轮未修改Java或Vue，未重复运行其构建；没有操作演示数据库。

**完整业务联调未执行。** 等用户确认第一封测试邮件实际收到后，再配置独立非demo、非H2数据库和测试邮箱白名单；保留REAL验证与授权校验，单独验证真实发送、失败重试和去重。演示账号及DEMO验证不迁移为真实授权，历史通知不批量外发。
