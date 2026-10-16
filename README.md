# 老年健康监测系统（个性化与慢性病联合评估）

本项目由 Vue 前端、Spring Boot 后端和一个 Python 联合模型服务组成。个性化生理预警与五种慢性病预测共用一张汇总表，一个按钮执行两类评估；两套已训练权重分别加载，平时无需重新训练。可使用 H2 演示模式，无需连接 MySQL。

## 快速启动：无 MySQL 演示

在 Windows 中依次双击以下脚本，并保持三个命令窗口运行：

1. `02_start_model_service.bat`：启动一个 Python 服务，同时加载两套模型（端口 8001）。
2. `03_start_demo_backend.bat`：启动 Java 后端（端口 21090）。
3. `04_start_demo_frontend.bat`：启动 Vue 前端（端口 21091）。

然后打开 <http://localhost:21091>，登录后进入“健康风险评估”，或直接访问 <http://localhost:21091/health-assessment>。演示账号、首次安装依赖、正式 MySQL 模式及常见问题见 [Windows 运行说明](docs/RUN_WINDOWS.md)。

首次启动演示后端时，脚本会从不含密钥的 `.example` 文件生成本机配置。正式连接 MySQL 或使用外部智能问答时，再在本机配置中填写账号和密钥；本机配置不会上传到 GitHub。

使用 IntelliJ IDEA 时，先将 `IHMS-admin/src/main/resources/` 中两个 `.yml.example` 文件复制为对应的 `.yml` 文件，再以 JDK 17 运行 `Application.java`，工作目录设为 `IHMS-admin`。默认启用 H2 演示模式；连接 MySQL 时需显式添加程序参数 `--spring.profiles.active=mysql`。前端可直接运行 `npm.cmd run dev`，默认启动和构建命令均已包含 OpenSSL 兼容参数。

## 目录用途

| 目录 | 内容 |
| --- | --- |
| `IHMS-view/` | Vue 前端页面与静态资源 |
| `IHMS-admin/` | Spring Boot 后端与演示数据库配置 |
| `risk-service/` | 联合模型服务入口及原个性化模型代码、权重 |
| `model-training/` | 现有五项综合分实验模型的离线训练与评价 |
| `chronic-disease/` | 独立的五病种风险预测研究：代码、配置、数据、报告和未来模型权重 |
| `sql/` | MySQL 初始化和升级脚本 |
| `scripts/` | 开发辅助脚本 |
| `docs/` | 详细运行说明、风险评估说明和原项目资料 |

`.venv/` 是本机 Python 虚拟环境，`.idea/` 是 PyCharm 项目设置；它们不属于业务源码。根目录启动脚本用于分别启动联合模型服务、Java后端和Vue前端。

更多说明：[训练与演示说明](docs/训练与演示说明.md) · [启动顺序](docs/START_ORDER.txt) · [慢性病预测独立目录](chronic-disease/README.md) · [慢性病风险预测改造方案与数据集](chronic-disease/docs/慢性病风险预测改造方案.md) · [原项目说明（历史资料）](docs/README_ORIGINAL.md)

个性化与慢性病已合并到“健康风险评估”。一次填写年龄、性别、BMI、健康生活资料、疾病史和本次实测值，点击“保存并联合预测”，同页展示个性化预警、近期实测趋势、五病种结果及两类评估历史。必填与选填逐项标注，全部字段直接显示。两类评估分别校验与提示错误，缺少一类所需信息时另一类仍可完成。旧 `/personalized-risk` 和 `/disease-risk` 地址自动跳转到新页面。

只需启动根目录 `02_start_model_service.bat`，不再另起8002。它使用 `chronic-disease/.venv` 同时加载原个性化权重与慢性病优化版本 `nhefs5-opt-20261015-v2`；`http://127.0.0.1:8001/health` 查看两套状态，`/docs` 查看同一份接口文档。旧的两个模型启动脚本均转到此入口，已就绪时不会重复启动。MySQL模式首次接入慢性病需执行 [慢性病建表脚本](chronic-disease/sql/disease_risk_upgrade_mysql.sql)，三端健康管理另需执行一次 [历史快照与请求去重升级](sql/health_review_upgrade_mysql.sql)。两个脚本均可重复执行，不清空业务数据；全部升级后为24张表、213个字段。H2模式自动升级。

## 三端健康管理

| 角色 | 入口 | 可用功能 |
| --- | --- | --- |
| 用户 | 健康风险评估 `/health-assessment` | 填写本人汇总表、一键联合预测、查看两类结果、测量趋势、分页历史及当次输入快照 |
| 医生 | 患者健康管理 `/doctor-patients` | 查询有咨询关联的患者；只读查看完整资料、疾病史、两类结果、历史快照、测量与日常健康记录 |
| 管理员 | 联合评估管理 `/healthReviewManage` | 按姓名、账号、用户ID、日期查询；查看患者资料、两类历史、病种及状态筛选、测量记录、统计和模型状态 |

三端查询共用 `/health-review` 接口、权限规则和结果组件，显示数据库已保存结果，查看时不重新运行模型。页面可见时每15秒刷新一次，回到页面时立即检查；同一浏览器保存后也会通知其他页面刷新。用户尚未保存的输入会保留。管理员首页新增健康数据汇总，“指标配置”仍用于日常指标规则，AI模型版本和性能入口位于“联合评估管理”。

两类评估各自通过 `requestId` 去重，提交结果不明确时重试相同请求不会重复添加记录。新个性化评估保存基础资料、本次测量及用于计算的既往测量快照；慢性病评估保留资料版本、五病种结果和输入快照。早期未保存快照的记录会明确提示无法还原当时输入。

各版本的准确率、召回率、AUC、混淆矩阵和曲线均保留在 [模型性能报告](chronic-disease/README.md) 中；接入页面不会重新训练或更改阈值。当前模型性能偏弱，仅用于研究演示，尚未验证中国社区老人实际风险。42个原始方向保留作备查，见[资料清单](chronic-disease/reports/nhefs/README.md)。接入配置、接口与回退说明见 [接入实施方案](chronic-disease/docs/前后端接入实施方案.md)。
