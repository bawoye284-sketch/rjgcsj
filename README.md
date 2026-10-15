# 老年健康监测系统（五项综合分实验预警版）

本项目由 Vue 前端、Spring Boot 后端和 Python 风险服务组成。`model-training` 用于离线训练，平时打开网站不需要重新训练。可使用 H2 演示模式，无需连接 MySQL。

## 快速启动：无 MySQL 演示

在 Windows 中依次双击以下脚本，并保持三个命令窗口运行：

1. `risk-service/02_start_risk_service.bat`：启动 Python 风险服务（端口 8001）。
2. `03_start_demo_backend.bat`：启动 Java 后端（端口 21090）。
3. `04_start_demo_frontend.bat`：启动 Vue 前端（端口 21091）。

然后打开 <http://localhost:21091>。演示账号、首次安装依赖、正式 MySQL 模式及常见问题见 [Windows 运行说明](docs/RUN_WINDOWS.md)。

首次启动演示后端时，脚本会从不含密钥的 `.example` 文件生成本机配置。正式连接 MySQL 或使用外部智能问答时，再在本机配置中填写账号和密钥；本机配置不会上传到 GitHub。

使用 IntelliJ IDEA 时，先将 `IHMS-admin/src/main/resources/` 中两个 `.yml.example` 文件复制为对应的 `.yml` 文件，再以 JDK 17 运行 `Application.java`，工作目录设为 `IHMS-admin`。默认启用 H2 演示模式；连接 MySQL 时需显式添加程序参数 `--spring.profiles.active=mysql`。前端可直接运行 `npm.cmd run dev`，默认启动和构建命令均已包含 OpenSSL 兼容参数。

## 目录用途

| 目录 | 内容 |
| --- | --- |
| `IHMS-view/` | Vue 前端页面与静态资源 |
| `IHMS-admin/` | Spring Boot 后端与演示数据库配置 |
| `risk-service/` | 加载已训练模型并提供预测接口 |
| `model-training/` | 五项综合分实验模型的离线训练与测试指标 |
| `sql/` | MySQL 初始化和升级脚本 |
| `scripts/` | 开发辅助脚本 |
| `docs/` | 详细运行说明、风险评估说明和原项目资料 |

`.venv/` 是本机 Python 虚拟环境，`.idea/` 是 PyCharm 项目设置；它们不属于业务源码。根目录的两个 `.bat` 保留在这里，方便直接双击启动演示。

更多说明：[训练与演示说明](docs/训练与演示说明.md) · [启动顺序](docs/START_ORDER.txt) · [慢性病风险预测改造方案与数据集](docs/慢性病风险预测改造方案.md) · [原项目说明（历史资料）](docs/README_ORIGINAL.md)
