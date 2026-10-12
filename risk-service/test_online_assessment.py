import unittest

from app import RiskRequest, health, personal_change, predict
from five_component import ASSESSMENT_BASIS


def request(current=None, history=None):
    return RiskRequest(
        user_id=2,
        profile={'age': 72, 'sex_male': 1, 'bmi': 25.4},
        current=current or {
            'measured_at': '2026-10-06 09:00:00',
            'systolic_pressure': 132, 'diastolic_pressure': 81,
            'heart_rate': 78, 'spo2': 97, 'temperature': 36.6,
            'respiratory_rate': 16,
        },
        history=history or [],
    )


class OnlineAssessmentTests(unittest.TestCase):
    def test_new_model_is_loaded_and_used_with_recent_change(self):
        self.assertTrue(health()['model_ready'])
        self.assertEqual(health()['training_basis'], 'NUMERIC5_FUTURE_AGGREGATE_V1')
        history = [
            {'measured_at': '2026-10-01 09:00:00', 'systolic_pressure': 124,
             'diastolic_pressure': 76, 'heart_rate': 72, 'spo2': 98,
             'temperature': 36.5, 'respiratory_rate': 16},
            {'measured_at': '2026-10-02 09:00:00', 'systolic_pressure': 126,
             'diastolic_pressure': 77, 'heart_rate': 73, 'spo2': 98,
             'temperature': 36.5, 'respiratory_rate': 16},
            {'measured_at': '2026-10-03 09:00:00', 'systolic_pressure': 127,
             'diastolic_pressure': 78, 'heart_rate': 74, 'spo2': 98,
             'temperature': 36.4, 'respiratory_rate': 16},
        ]
        result = predict(request(history=history))
        self.assertEqual(result['assessment_basis'], ASSESSMENT_BASIS)
        self.assertTrue(result['baseline_available'])
        self.assertTrue(0 <= result['group_risk_probability'] <= 1)
        self.assertIn(result['risk_level'], ('正常', '关注', '高风险'))

    def test_imputed_measurements_do_not_prove_normal(self):
        current = request().current.model_dump()
        current['respiratory_rate'] = None
        result = predict(request(current=current))
        self.assertFalse(result['news2_complete'])
        self.assertNotEqual(result['risk_level'], '正常')

    def test_personal_baseline_excludes_measurements_older_than_30_days(self):
        history = [
            {'measured_at': f'2026-08-0{day} 09:00:00', 'heart_rate': 60}
            for day in (1, 2, 3)
        ]
        score, details, available = personal_change(request(history=history))
        self.assertEqual((score, details, available), (0.0, [], False))


if __name__ == '__main__':
    unittest.main()
