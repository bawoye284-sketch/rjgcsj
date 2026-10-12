# 数据说明

- `splits/`：按人员划分的前 24 小时输入数据。原有 `aux_future_deterioration_label` 列不再用于训练。
- `future_five_score_labels.csv`：由后续 24–48 小时实测值生成的五项综合分标签、分数上下界及观测项数。空标签表示缺测影响结论，训练时排除。
- `SOURCE.txt`：原数据来源和使用范围。

标签生成方法见 `../../build_five_score_labels.py`。同一人的前期输入与后期标签通过 `user_id` 对应，训练、验证、测试之间没有重复人员。
