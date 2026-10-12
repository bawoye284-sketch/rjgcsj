import tempfile
import unittest
from pathlib import Path

import pandas as pd

from build_five_score_labels import build_labels


class FiveScoreLabelTests(unittest.TestCase):
    def test_combined_warning_and_uncertain_missing_values(self):
        rows = [
            # Four moderate components sum to five without a single 3-point alert.
            dict(user_id='warning', hour=24, resp_rate=22, spo2=95,
                 sbp=105, heart_rate=100, temperature=37),
            # One missing component cannot lift this four-component zero above 4.
            dict(user_id='normal', hour=24, resp_rate=16, spo2=98,
                 sbp=120, heart_rate=75, temperature=None),
            # Two missing components could change the result: leave unlabeled.
            dict(user_id='uncertain', hour=24, resp_rate=16, spo2=None,
                 sbp=120, heart_rate=75, temperature=None),
        ]
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as directory:
            source = Path(directory) / 'hourly.csv'
            pd.DataFrame(rows).to_csv(source, index=False)
            result = build_labels(source).set_index('user_id')
        self.assertEqual(result.loc['warning', 'score_lower_bound'], 5)
        self.assertEqual(result.loc['warning', 'future_five_score_warning'], 1)
        self.assertEqual(result.loc['normal', 'future_five_score_warning'], 0)
        self.assertTrue(pd.isna(result.loc['uncertain', 'future_five_score_warning']))


if __name__ == '__main__':
    unittest.main()
