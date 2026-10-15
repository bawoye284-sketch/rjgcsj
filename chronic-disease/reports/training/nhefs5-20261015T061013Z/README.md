# 模型性能交付

打开 performance_report.html 查看自包含报告。metrics_summary.csv 可直接用于汇报表格；metrics_summary.json 保留数值。

每个病种的 metrics.json 保留两种模型的指标、分类报告、混淆矩阵及95%重抽样区间；figures/ 包含PNG、PDF、SVG和曲线源数据。

run.json 保留开发集模型选择依据、参数搜索、阈值、数据摘要、代码摘要和环境版本。个人级预测保存在 data/nhefs/processed/runs/<版本>/predictions.jsonl，不进入Git。

这是历史美国完成随访生存者的研究原型结果；少事件任务应同时展示区间和事件数，不直接解释为中国老人五年发病概率。
