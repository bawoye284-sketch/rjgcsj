"""Build conservative future five-component score labels from measured vitals.

Each patient's 24-48h median for each available vital is scored separately.
This day-level representative sum is a project proxy, not a simultaneous or
complete NEWS2 assessment. Missing components are never assigned zero.
"""

import argparse
from pathlib import Path
import sys

import pandas as pd

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / 'risk-service'))
from news2 import score_numeric5_values

DEFAULT_SOURCE = ROOT.parents[2] / 'sjj' / '逐小时数据' / 'news2_hourly_numeric_components_0_48h.csv'
DEFAULT_OUTPUT = ROOT / 'data' / 'news2_proxy' / 'future_five_score_labels.csv'
FIELDS = ['resp_rate', 'spo2', 'sbp', 'heart_rate', 'temperature']


def build_labels(source):
    hourly = pd.read_csv(source, usecols=['user_id', 'hour', *FIELDS])
    future = hourly.loc[hourly['hour'].between(24, 48)]
    typical = future.groupby('user_id', sort=True)[FIELDS].median()
    rows = []
    for user_id, row in typical.iterrows():
        component_scores = score_numeric5_values(
            respiratory_rate=row['resp_rate'], spo2=row['spo2'],
            systolic_pressure=row['sbp'], heart_rate=row['heart_rate'],
            temperature=row['temperature'],
        )
        observed = len(component_scores)
        lower = sum(component_scores.values())
        upper = lower + 3 * (5 - observed)
        # The five-component threshold is 5. Retain only logically certain
        # outcomes; do not infer a negative label from missing measurements.
        label = (1 if lower >= 5 else 0 if upper < 5 else None) if observed >= 3 else None
        rows.append({
            'user_id': user_id,
            'observed_components': observed,
            'score_lower_bound': lower,
            'score_upper_bound': upper,
            'exact_five_score': lower if observed == 5 else None,
            'future_five_score_warning': label,
        })
    return pd.DataFrame(rows)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, default=DEFAULT_SOURCE)
    parser.add_argument('--output', type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    if not args.source.is_file():
        raise FileNotFoundError(f'逐小时数据不存在: {args.source}')
    labels = build_labels(args.source)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    labels.to_csv(args.output, index=False)
    print(f'标签表: {args.output}')
    print(f'总人数: {len(labels)}；可确定标签: {labels.future_five_score_warning.notna().sum()}；'
          f'完整五项: {labels.exact_five_score.notna().sum()}')


if __name__ == '__main__':
    main()
