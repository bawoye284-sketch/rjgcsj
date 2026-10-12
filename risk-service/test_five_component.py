import unittest
from types import SimpleNamespace

from five_component import assess_observed_five, combine_risk


def observation(**changes):
    fields = dict(
        respiratory_rate=16, spo2=98, systolic_pressure=120,
        diastolic_pressure=78, heart_rate=72, temperature=36.5,
    )
    fields.update(changes)
    return SimpleNamespace(**fields)


class FiveComponentTests(unittest.TestCase):
    def test_does_not_require_oxygen_or_consciousness(self):
        actual = assess_observed_five(observation())
        self.assertTrue(actual['complete'])
        self.assertEqual(actual['numeric_total'], 0)
        self.assertEqual(actual['single_alerts'], [])

    def test_model_and_history_jointly_raise_level(self):
        actual = assess_observed_five(observation())
        self.assertEqual(combine_risk(0.2, 0.46, 0.1, True, actual), '正常')
        self.assertEqual(combine_risk(0.6, 0.46, 0.1, True, actual), '关注')
        self.assertEqual(combine_risk(0.2, 0.46, 0.6, True, actual), '关注')
        self.assertEqual(combine_risk(0.6, 0.46, 0.6, True, actual), '高风险')

    def test_several_moderate_findings_add_to_five_point_warning(self):
        actual = assess_observed_five(observation(
            respiratory_rate=22, spo2=95, systolic_pressure=105,
            heart_rate=100,
        ))
        self.assertEqual(actual['numeric_total'], 5)
        self.assertEqual(actual['single_alerts'], [])
        self.assertEqual(combine_risk(0.1, 0.46, 0.0, False, actual), '关注')

    def test_observed_red_warning_is_never_suppressed_by_model(self):
        actual = assess_observed_five(observation(respiratory_rate=25))
        self.assertTrue(actual['single_alerts'])
        self.assertEqual(combine_risk(0.1, 0.46, 0.0, False, actual), '关注')

    def test_missing_values_are_not_imputed_in_observed_safety_check(self):
        partial = assess_observed_five(observation(respiratory_rate=None, spo2=None))
        self.assertFalse(partial['complete'])
        self.assertEqual(partial['observed_count'], 3)
        self.assertIsNone(partial['numeric_total'])
        self.assertEqual(combine_risk(0.2, 0.46, 0.0, False, partial), '暂无法评估')
        too_sparse = assess_observed_five(observation(respiratory_rate=None, spo2=None,
                                                     systolic_pressure=None))
        self.assertEqual(combine_risk(0.2, 0.46, 0.0, False, too_sparse), '暂无法评估')

    def test_news2_numeric_boundary_values(self):
        for field, low, high in (
            ('respiratory_rate', 20, 21),
            ('spo2', 96, 95),
            ('systolic_pressure', 111, 110),
            ('heart_rate', 90, 91),
            ('temperature', 38.0, 38.1),
        ):
            self.assertEqual(assess_observed_five(observation(**{field: low}))['numeric_total'], 0)
            self.assertGreater(assess_observed_five(observation(**{field: high}))['numeric_total'], 0)

    def test_severe_observed_value_keeps_single_alert(self):
        for changes in ({'respiratory_rate': 25}, {'spo2': 91},
                        {'systolic_pressure': 220}, {'heart_rate': 131},
                        {'temperature': 35}):
            self.assertTrue(assess_observed_five(observation(**changes))['single_alerts'])


if __name__ == '__main__':
    unittest.main()
