"""Observed five-component warning checks and model/history fusion.

The five-component subtotal uses NEWS2 Scale 1 numeric ranges but is NOT a
complete NEWS2 score. Missing observations never become measured normal values.
"""

from news2 import assess_numeric5


ASSESSMENT_BASIS = 'NUMERIC5_AGGREGATE_HISTORY_V1'


def assess_observed_five(measurement):
    return assess_numeric5(measurement)


def combine_risk(model_score, model_threshold, change_score, history_available, observed):
    """Conservative demonstration policy; its cutoffs are not clinical rules.

    Model and recent change are the two assessment components. Observed severe
    vital-sign alerts act as a safety floor, never as imputed measurements.
    """
    if observed['observed_count'] < 3:
        return '暂无法评估'
    numeric_total = observed['numeric_total']
    model_warning = model_score >= model_threshold
    history_warning = history_available and change_score >= 0.50
    if numeric_total is not None and numeric_total >= 7:
        return '高风险'
    if model_warning and history_warning:
        return '高风险'
    if model_warning or history_warning or observed['single_alerts']:
        return '关注'
    if numeric_total is not None and numeric_total >= 5:
        return '关注'
    # An imputed value can support a model estimate, but cannot certify that
    # the current observation is free of warning signs.
    return '正常' if observed['complete'] else '暂无法评估'
