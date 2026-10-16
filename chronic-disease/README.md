# 慢性病风险预测研究

本目录独立存放慢性病预测的数据、代码、配置、报告和未来模型权重。当前候选病种为高血压、糖尿病、卒中、心脏病发作和骨质疏松，任务为多标签风险预测。

已完成官方字段核对、65岁及以上队列审计、五病种训练、独立留出评价、独立预测服务及网站接入。每病比较逻辑回归与XGBoost，保存完整指标和图表。当前模型性能偏弱，属于历史美国完成随访生存者的新报告预测原型，尚不能用于中国老人实际风险判断。数据、训练代码、权重与报告仍独立放在本目录；网站通过新增接口接入，`risk-service/model/` 保留。

当前正式版本为 `nhefs5-20261015T061013Z`。直接打开[模型性能报告](reports/training/nhefs5-20261015T061013Z/performance_report.html)，或查看[汇总表](reports/training/nhefs5-20261015T061013Z/metrics_summary.csv)和[队列审计](reports/nhefs/cohort_audit.md)。首轮版本保留用于过程追溯，当前版本对训练与在线输入采用相同的最少资料规则。

网站固定接入的优化实验版本为 `nhefs5-opt-20261015-v2`：[优化报告](reports/training/nhefs5-opt-20261015-v2/performance_report.html)、[与原版配对比较](reports/training/nhefs5-opt-20261015-v2/optimization_comparison.md)、[四种阈值结果](reports/training/nhefs5-opt-20261015-v2/operating_points.csv)。原留出集已经查看，本轮明确属于探索性比较，需要新数据验证。原版权重、报告和 `active_model.json` 指针均保留，网站通过环境变量固定加载优化版。

本轮增加5项经过1987官方问卷核对的基线字段；比较精简、原有、扩展三组特征，强正则化和类别权重。参数与特征选择、缺失处理和校准都在3×3嵌套开发集交叉验证内完成。默认阈值在开发集特异度至少80%的范围内最大化Youden指数，同时保留平衡、70%召回和F1策略；不以留出准确率选择阈值。

结果为部分改善：按开发集所选模型比较，卒中AUC从0.587升至0.714（原版XGBoost单独为0.667），糖尿病AUC从0.543升至0.611；高血压、心脏病发作和骨质疏松的AUC未改善。默认策略下高血压虽有91.23%准确率，召回率却为0，不可据此宣称预测有效。完整报告保留全部退步结果和阈值代价，本轮候选未替换活动版本。

## 目录

```text
chronic-disease/
├─ src/                       下载、队列处理、训练评价和独立服务
├─ config/                    五个候选病种选择与字段规则
├─ data/nhefs/
│  ├─ raw/                    官方原始数据
│  ├─ documentation/          官方字段布局和手册
│  ├─ derived/                按轮提取的原始字段及编码统计
│  ├─ download_manifest.json  下载来源、文件摘要和核对信息
│  └─ README.md               数据说明
├─ reports/nhefs/             可追溯的疾病清单与字段字典
├─ models/                    版本权重和active_model.json
└─ docs/                      改造方案
```

入口文件：

- [五病种选择及字段配置](config/nhefs_disease_targets.json)：`first_version_target_codes` 只选择5项；其余37个资料方向保留作备查。
- [数据来源与文件说明](data/nhefs/README.md)。
- [提取报告](reports/nhefs/README.md)、[逐病种字段目录](reports/nhefs/catalog.json)、[字段字典](reports/nhefs/field_dictionary.json)。
- [完整实施方案](docs/慢性病风险预测改造方案.md)：包含数据审核、训练评价、模型版本、独立服务和可选网站接入的文件与验收条件。
- [前后端接入实施方案](docs/前后端接入实施方案.md)：四张新表、七个接口、页面字段、版本固定、运行与回退说明。
- [模型权重存放约定](models/README.md)。

## 使用

下载和字段提取脚本只使用标准库；训练、绘图和服务使用独立 `.venv/`。`requirements.txt` 固定主要依赖，`requirements-lock.txt` 记录完整环境，不修改旧模型依赖。

首次准备独立环境：

```powershell
python -m venv .\chronic-disease\.venv
& .\chronic-disease\.venv\Scripts\python.exe -m pip install -r .\chronic-disease\requirements-lock.txt
```

从 `rjgcsj` 完整执行审计、训练和性能报告生成，可双击 `chronic-disease/01_train_models.bat`，或运行：

```powershell
& .\chronic-disease\.venv\Scripts\python.exe -B .\chronic-disease\src\run_pipeline.py
```

每次建立新版本，旧权重和报告不覆盖。

训练默认使用 `config/training_optimized.json` 的优化方案，**不会自动替换活动模型**。`--recipe baseline` 可复现原有搜索方案；显式添加 `--activate` 才更新 `active_model.json`。优化方案会核对原版队列快照，标签、参与者或划分发生变化时中止比较。

只重建已有版本的指标与图表：

```powershell
& .\chronic-disease\.venv\Scripts\python.exe -B .\chronic-disease\src\evaluate_models.py --version nhefs5-20261015T061013Z
```

网站现在只运行一个8001联合模型服务。双击项目根目录 `02_start_model_service.bat`；本目录 `02_start_service.bat` 及原 `risk-service/02_start_risk_service.bat` 均转到同一入口。使用本目录 `.venv` 同时加载原个性化模型和慢性病优化版 `nhefs5-opt-20261015-v2`，权重仍分别存放。打开 <http://127.0.0.1:8001/docs> 查看两类接口，`/health` 包含两套就绪状态，`/models` 返回五病种定义和个性化模型状态。无需另起8002。

网站入口为 <http://localhost:21091/health-assessment>，使用现有用户账号登录；旧 `/disease-risk` 和 `/personalized-risk` 自动跳转。汇总表一次填写年龄、性别、BMI、健康生活资料、疾病史和实测值，点击“保存并联合预测”，两类结果同页显示。BMI与体重分别使用，两类评估独立校验；一类缺资料或请求失败时另一类仍可完成。MySQL 模式首次执行 `sql/disease_risk_upgrade_mysql.sql`，已有24表无需新迁移；H2模式自动建表。模型说明继续展示年龄、AUC、召回率和版本局限。

## 性能报告

- `reports/training/<version>/performance_report.html`：内嵌图表的中文报告，可离线打开。
- `metrics_summary.csv/json`：两种模型的12项指标、混淆矩阵计数和95%区间。
- `<disease>/metrics.json`：完整指标、分类报告、阈值、参数搜索和500次参与者重抽样区间。
- `<disease>/figures/`：两种模型的混淆矩阵，以及ROC、PR、校准、开发集阈值图；保存PNG、PDF、SVG、600dpi TIFF和曲线源数据。
- `run.json`、`configuration.json`：划分方法、参数、环境、配置、代码与数据摘要。
- `data/nhefs/processed/runs/<version>/`：正式队列快照和个人级预测，留本地复核。
- 优化版本另存 `optimization_comparison.md/csv/json`、`operating_points.csv`，以及本地逐病种/模型的嵌套折划分和搜索审计。AUC变化提供同一人员500次配对重抽样区间。

模型和阈值依据开发集确定，留出集不参与选择。报告附常数事件率/全部判阴性基准；少事件任务需同时展示事件数和区间。

如需单独加载优化实验，可在新的PowerShell终端设置版本后运行独立服务；该命令不改现有活动指针：

```powershell
$env:CHRONIC_MODEL_VERSION = 'nhefs5-opt-20261015-v2'
& .\chronic-disease\.venv\Scripts\python.exe -B -m uvicorn api:app --app-dir .\chronic-disease\src --host 127.0.0.1 --port 8003
```

扩展输入均可选：`high_cholesterol_history`（医生告知高胆固醇史）、`health_worsened`（过去一年健康变差）、`weight_gain_10lb`/`weight_loss_10lb`（过去一年增/减至少10磅，约4.54kg，两者不能同时为1）、`hip_fracture_history`（髋部骨折史）。0为明确否、1为是、未知填null；高胆固醇史不是化验值。输入过少的判定仍沿用原先5项健康/生活习惯字段，不改变训练队列入组规则。

从项目根目录 `rjgcsj` 执行字段提取：

```powershell
& .\.venv\Scripts\python.exe .\chronic-disease\src\prepare_nhefs_disease_inventory.py
```

程序读取本目录配置和已下载数据，校验文件摘要，将个人级记录写入 `data/nhefs/derived/`，将清单和字典写入 `reports/nhefs/`。原始问卷编码保留为字符串，尚不生成患病0/1标签。

只有需要重新获取数据时才执行下载；重新执行会重新下载官方文件：

```powershell
& .\.venv\Scripts\python.exe .\chronic-disease\src\download_nhefs.py
```

可用 `--help` 查看参数。下载脚本支持 `--data-dir`、`--workers`；提取脚本支持 `--data-dir`、`--report-dir`。

## 文件管理

源码、配置、说明、下载清单和汇总报告保留在 Git。原始数据、官方手册、个人级派生记录和模型权重由本目录 `.gitignore` 排除。源码目录不保留临时核验脚本、测试样本或缓存。

独立研究流程已完成，后续重点是完善输入、扩大有效事件和开展目标人群验证。网站接入仍为单独阶段。权重只写入本目录 `models/`。
