# 优化实验性能交付

本轮重复使用已查看的原留出集，属于探索性比较，不能称为新的独立验证。

performance_report.html：完整图表；optimization_comparison.md/csv/json：与原版配对对比；operating_points.csv：四种开发集阈值的误报/漏报取舍。

metrics_summary.csv/json和各病metrics.json保留完整性能指标、分类报告、混淆矩阵和95%重抽样区间。figures/保留PNG/PDF/SVG/TIFF及曲线源数据；run.json记录嵌套开发集搜索与参数。

权重和个人级预测/折划分审计在独立models/与processed/运行目录；原版本完整保留，默认不更换活动模型。
