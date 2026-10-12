"""Five observed numeric components of NEWS2 Scale 1 (RCP 2017).

The score remains internal. The application exposes only the risk band and
single-parameter alerts; incomplete observations never become a normal band.
"""

from math import isfinite


LABELS = {
    'respiratory_rate': '呼吸频率',
    'spo2': '血氧',
    'systolic_pressure': '收缩压',
    'heart_rate': '心率',
    'temperature': '体温',
}


def _number(value, lower, upper):
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if isfinite(number) and lower <= number <= upper else None


def score_numeric5_values(respiratory_rate=None, spo2=None,
                          systolic_pressure=None, heart_rate=None,
                          temperature=None):
    """Return observed five-component NEWS2 Scale 1 scores, excluding O2/AVCPU."""
    rr = _number(respiratory_rate, 1, 80)
    spo2 = _number(spo2, 1, 100)
    sbp = _number(systolic_pressure, 1, 300)
    pulse = _number(heart_rate, 1, 300)
    temp = _number(temperature, 25, 45)
    scores = {}
    if rr is not None:
        scores['respiratory_rate'] = 3 if rr <= 8 or rr >= 25 else 1 if rr <= 11 else 0 if rr <= 20 else 2
    if spo2 is not None:
        scores['spo2'] = 3 if spo2 <= 91 else 2 if spo2 <= 93 else 1 if spo2 <= 95 else 0
    if sbp is not None:
        scores['systolic_pressure'] = 3 if sbp <= 90 or sbp >= 220 else 2 if sbp <= 100 else 1 if sbp <= 110 else 0
    if pulse is not None:
        if pulse <= 40 or pulse >= 131:
            scores['heart_rate'] = 3
        elif pulse <= 50:
            scores['heart_rate'] = 1
        elif pulse <= 90:
            scores['heart_rate'] = 0
        elif pulse <= 110:
            scores['heart_rate'] = 1
        else:
            scores['heart_rate'] = 2
    if temp is not None:
        if temp <= 35:
            scores['temperature'] = 3
        elif temp <= 36:
            scores['temperature'] = 1
        elif temp <= 38:
            scores['temperature'] = 0
        elif temp <= 39:
            scores['temperature'] = 1
        else:
            scores['temperature'] = 2
    return scores


def assess_numeric5(measurement):
    """Score only measured numeric components; never treat missing as normal."""
    if measurement is None:
        return {'complete': False, 'observed_count': 0, 'numeric_total': None, 'single_alerts': []}

    scores = score_numeric5_values(
        respiratory_rate=measurement.respiratory_rate,
        spo2=measurement.spo2,
        systolic_pressure=measurement.systolic_pressure,
        heart_rate=measurement.heart_rate,
        temperature=measurement.temperature,
    )
    sbp = _number(measurement.systolic_pressure, 1, 300)
    dbp = _number(measurement.diastolic_pressure, 1, 200)
    alerts = [f'{LABELS[key]}出现单项警报，请尽快联系医护人员复核'
              for key, value in scores.items() if value == 3]
    # Separate AHA severe-BP safety override: NEWS2 itself does not score
    # diastolic pressure, and systolic 181-219 scores zero. Do not display
    # "normal" with this alert. Source: heart.org/.../when-to-call-911-for-high-blood-pressure
    severe_bp = (sbp is not None and sbp > 180) or (dbp is not None and dbp > 120)
    if severe_bp and scores.get('systolic_pressure') != 3:
        alerts.append('血压严重升高，请复测并尽快联系医护人员')
    complete = len(scores) == 5
    return {
        'complete': complete,
        'observed_count': len(scores),
        'numeric_total': sum(scores.values()) if complete else None,
        'single_alerts': alerts,
    }
