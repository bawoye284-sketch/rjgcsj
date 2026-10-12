# 五项综合分实验模型训练

模型输入仍是前 24 小时的五项生理指标均值，加年龄、性别、BMI、舒张压。训练答案改为后续 24–48 小时的**五项综合分是否达到 5 分**，不再使用“出现两次严重异常”的旧答案。五项分别是呼吸频率、血氧、收缩压、心率、体温。吸氧和意识状态未纳入，故不是完整 NEWS2。

标签从每人后续一天各项**实际观测值的中位数**计算五项分数后相加。这是项目定义的“一天代表状态”，不是同一时刻的正式 NEWS2 测量。某项未测量时不记作 0 分：程序计算综合分的上下界；至少三项有实测，且上下界不会改变是否达到 5 分的判断时才保留标签。由此得到 618 人的可用标签，其中训练 445 人、验证 82 人、测试 91 人。测试指标只适用于这一筛选后的人群。

数据处理和训练从项目根目录运行：

```powershell
& .\.venv\Scripts\python.exe .\model-training\build_five_score_labels.py
& .\.venv\Scripts\python.exe .\model-training\train_five_score_model.py
& .\.venv\Scripts\python.exe .\model-training\plot_model_evaluation.py
```

第一步默认读取旁边 `sjj/逐小时数据/news2_hourly_numeric_components_0_48h.csv`，生成项目内的 `data/news2_proxy/future_five_score_labels.csv`；如数据位置不同，可用 `--source` 指定。已经生成的标签表随项目保存，因此只重新训练时可以直接运行第二步。PyCharm 运行配置选择 `train_five_score_model.py`，解释器为项目 `.venv/Scripts/python.exe`。

训练程序先在训练集内部进行 3 折分层交叉验证，搜索 32 组参数。参数矩阵包含树深度、子节点最小权重、学习率、样本抽样比例和正则化强度；每一折都只用该折的训练数据拟合缺失值处理，再用折内留出的数据计算 PR-AUC。按平均 PR-AUC 选出参数组合后，使用完整训练集重新训练。最大训练轮数为 700 轮，连续 60 轮未改善时提前停止；最佳轮数仍由数据决定，不是人为要求训练满 700 轮。

原验证集用于最终模型的提前停止和选择召回率不低于 75% 的分类阈值；测试集只在参数和阈值确定后作一次最终评估。每组参数及交叉验证结果写入 `output/news2-five-score/grid_search_results.csv`，最优参数、最佳轮数和测试指标写入同目录的 `metrics.json`。混淆矩阵、测试集 ROC/AUC、迭代与样本量学习曲线位于 `figures/` 子目录，均有 PNG 和 PDF 版本。双击 `01_train_model.bat` 会顺序执行搜索、训练和绘图；搜索 32 组参数比原来的单次训练耗时更长。确认结果后，将 `five_score_future_model.joblib` 与 `metrics.json` 复制到 `risk-service/model/`，重启风险服务。启动网站本身不需要重新训练。

训练来源是住院重症患者，五项同时完整的后续记录很少；训练答案是筛选后的简化评分。测试正确率不等于社区老人健康判断的准确率，使用前需要收集社区老人连续、五项完整的测量并重新验证。
