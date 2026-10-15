# 模型权重

本目录已保存五病种的逻辑回归与XGBoost校准模型。`active_model.json` 指定独立服务版本，`<版本>/metadata.json` 保存来源、适用范围与性能。当前为研究原型，性能偏弱。

后续每次训练使用独立的版本目录，集中保存模型、预处理、输入字段、结局定义、适用人群、预测时间范围、评价指标和阈值。例如：

```text
models/
└─ <训练版本>/
   ├─ hypertension.joblib
   ├─ diabetes.joblib
   ├─ stroke.joblib
   ├─ heart_attack.joblib
   ├─ osteoporosis.joblib
   └─ metadata.json
```

实际文件使用 `<病种>--LogisticRegression.joblib` 和 `<病种>--XGBoost.joblib` 命名，模型包包含预处理和校准。元数据中的selected_model依据开发集确定，数据不足时记录跳过原因。

`nhefs5-opt-20261015-v2` 是优化实验，原留出集曾被查看，不能当作新的独立验证。三个固定特征组、正则化和类别权重通过嵌套开发集交叉验证选择；四种开发集阈值保存在包内及元数据中，默认specificity80。每个模型可使用不同的特征子集，包内`features`是推理所需字段。

训练默认只产生新版本，保留原活动指针；只有添加`--activate`才更新`active_model.json`。也可使用环境变量`CHRONIC_MODEL_VERSION`在独立端口加载指定版本进行研究。不能按已查看的留出性能反复挑选版本并声称独立验证。

训练产物由 `chronic-disease/.gitignore` 排除；现有生理预警权重继续位于 `risk-service/model/`，不从这里自动替换或部署。
