"""Predict a future five-component aggregate-score warning from day-one data.

Only logically determined labels are used. A future-day median for each observed
vital is scored, then summed. Missing components contribute an uncertainty range,
not a fabricated zero. This is a day-level project proxy, not full NEWS2.
"""

from pathlib import Path
import json
import sys

import joblib
import numpy as np
import pandas as pd
from sklearn.metrics import (
    accuracy_score, balanced_accuracy_score, confusion_matrix, f1_score,
    precision_score, recall_score, roc_auc_score, average_precision_score,
)
from sklearn.model_selection import ParameterGrid, StratifiedKFold
from xgboost import XGBClassifier


ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / 'risk-service'))
from feature_imputer import VitalFeatureImputer
DATA = ROOT / 'data' / 'news2_proxy' / 'splits'
LABELS = ROOT / 'data' / 'news2_proxy' / 'future_five_score_labels.csv'
OUTPUT = ROOT / 'output' / 'news2-five-score'
FEATURES = [
    'age', 'sex', 'bmi', 'sbp_mean', 'dbp_mean', 'heart_rate_mean',
    'spo2_mean', 'resp_rate_mean', 'temperature_mean',
]
TARGET = 'future_five_score_warning'
RANDOM_STATE = 42
CV_FOLDS = 3
MAX_ROUNDS = 700
EARLY_STOPPING_ROUNDS = 60
PARAMETER_GRID = {
    'max_depth': [2, 3],
    'min_child_weight': [1, 3],
    'learning_rate': [0.035, 0.07],
    'subsample': [0.8, 0.9],
    'reg_lambda': [1.0, 3.0],
}
FIXED_PARAMETERS = {
    'objective': 'binary:logistic',
    'eval_metric': 'aucpr',
    'tree_method': 'hist',
    'n_estimators': MAX_ROUNDS,
    'colsample_bytree': 0.9,
    'random_state': RANDOM_STATE,
    'n_jobs': 4,
    'early_stopping_rounds': EARLY_STOPPING_ROUNDS,
}


def read_split(name):
    path = DATA / f'aux_{name}_future_deterioration.csv'
    frame = pd.read_csv(path)
    missing = set(FEATURES + ['user_id']) - set(frame.columns)
    if missing:
        raise ValueError(f'{path} lacks columns: {sorted(missing)}')
    if frame['user_id'].duplicated().any():
        raise ValueError(f'{path} has duplicate patient ids')
    labels = pd.read_csv(LABELS, usecols=['user_id', TARGET])
    if labels['user_id'].duplicated().any():
        raise ValueError(f'{LABELS} has duplicate patient ids')
    frame = frame.merge(labels, on='user_id', how='left', validate='one_to_one')
    frame = frame.loc[frame[TARGET].notna()].reset_index(drop=True)
    if not frame[TARGET].isin([0, 1]).all():
        raise ValueError(f'{path} has invalid target values')
    if frame[TARGET].nunique() != 2:
        raise ValueError(f'{name} has only one label class')
    return frame


def report(y, probability, threshold):
    prediction = (probability >= threshold).astype(int)
    tn, fp, fn, tp = confusion_matrix(y, prediction, labels=[0, 1]).ravel()
    return {
        'accuracy': float(accuracy_score(y, prediction)),
        'balanced_accuracy': float(balanced_accuracy_score(y, prediction)),
        'precision': float(precision_score(y, prediction, zero_division=0)),
        'recall': float(recall_score(y, prediction, zero_division=0)),
        'specificity': float(tn / (tn + fp)) if tn + fp else 0.0,
        'f1': float(f1_score(y, prediction, zero_division=0)),
        'roc_auc': float(roc_auc_score(y, probability)),
        'pr_auc': float(average_precision_score(y, probability)),
        'confusion_matrix': [[int(tn), int(fp)], [int(fn), int(tp)]],
    }


def select_threshold(y, probability, minimum_recall=0.75):
    choices = []
    for threshold in np.unique(probability):
        stats = report(y, probability, threshold)
        if stats['recall'] >= minimum_recall:
            choices.append((stats['f1'], stats['accuracy'], stats['recall'], threshold))
    if not choices:
        raise ValueError('validation split cannot satisfy the recall floor')
    return float(max(choices)[3])


def class_weight_ratio(y):
    return float((y == 0).sum() / max((y == 1).sum(), 1))


def search_parameters(x_train, y_train):
    """Choose model parameters using only training patients and fold-local imputers."""
    splitter = StratifiedKFold(n_splits=CV_FOLDS, shuffle=True, random_state=RANDOM_STATE)
    folds = []
    for fit_indices, score_indices in splitter.split(x_train, y_train):
        fold_imputer = VitalFeatureImputer(FEATURES).fit(x_train.iloc[fit_indices])
        folds.append((
            fold_imputer.transform(x_train.iloc[fit_indices]), y_train[fit_indices],
            fold_imputer.transform(x_train.iloc[score_indices]), y_train[score_indices],
        ))

    grid = list(ParameterGrid(PARAMETER_GRID))
    results = []
    for number, parameters in enumerate(grid, start=1):
        ap_scores, roc_scores, best_rounds = [], [], []
        for fit_x, fit_y, score_x, score_y in folds:
            model = XGBClassifier(
                **FIXED_PARAMETERS, **parameters,
                scale_pos_weight=class_weight_ratio(fit_y),
            )
            model.fit(fit_x, fit_y, eval_set=[(score_x, score_y)], verbose=False)
            probability = model.predict_proba(score_x)[:, 1]
            ap_scores.append(float(average_precision_score(score_y, probability)))
            roc_scores.append(float(roc_auc_score(score_y, probability)))
            best_rounds.append(int(model.best_iteration + 1))
        result = {
            'candidate': number, **parameters,
            'cv_pr_auc_mean': float(np.mean(ap_scores)),
            'cv_pr_auc_std': float(np.std(ap_scores)),
            'cv_roc_auc_mean': float(np.mean(roc_scores)),
            'cv_best_rounds_median': int(np.median(best_rounds)),
        }
        results.append(result)
        print(f'参数组合 {number}/{len(grid)}: '
              f'交叉验证 PR-AUC={result["cv_pr_auc_mean"]:.3f}, '
              f'ROC-AUC={result["cv_roc_auc_mean"]:.3f}, '
              f'参数={parameters}', flush=True)

    # Resolve ties deterministically; the independent validation and test splits
    # must not influence which grid candidate wins.
    winner = max(results, key=lambda item: (
        item['cv_pr_auc_mean'], item['cv_roc_auc_mean'],
        -item['cv_pr_auc_std'], -item['max_depth'], -item['candidate'],
    ))
    selected = {name: winner[name] for name in PARAMETER_GRID}
    return selected, winner, results


def main():
    train, validation, test = (read_split(name) for name in ('train', 'validation', 'test'))
    sets = [set(part['user_id']) for part in (train, validation, test)]
    if any(sets[i] & sets[j] for i, j in ((0, 1), (0, 2), (1, 2))):
        raise ValueError('patient overlap between training, validation, and test splits')

    x_train = train[FEATURES].apply(pd.to_numeric, errors='coerce')
    x_validation = validation[FEATURES].apply(pd.to_numeric, errors='coerce')
    x_test = test[FEATURES].apply(pd.to_numeric, errors='coerce')
    y_train = train[TARGET].astype(int).to_numpy()
    y_validation = validation[TARGET].astype(int).to_numpy()
    y_test = test[TARGET].astype(int).to_numpy()

    selected_parameters, cv_winner, search_results = search_parameters(x_train, y_train)

    # Refit preprocessing on the whole training split after cross-validation.
    # Added missingness flags distinguish observations from imputations online.
    imputer = VitalFeatureImputer(FEATURES).fit(x_train)
    a = imputer.transform(x_train)
    b = imputer.transform(x_validation)
    c = imputer.transform(x_test)
    model = XGBClassifier(
        **FIXED_PARAMETERS, **selected_parameters,
        scale_pos_weight=class_weight_ratio(y_train),
    )
    model.fit(a, y_train, eval_set=[(b, y_validation)], verbose=False)
    validation_probability = model.predict_proba(b)[:, 1]
    threshold = select_threshold(y_validation, validation_probability)
    test_probability = model.predict_proba(c)[:, 1]
    imputation_reference = {}
    for field in ('resp_rate_mean', 'spo2_mean'):
        observed = x_validation[field].dropna()
        median = float(imputer.statistics_[FEATURES.index(field)])
        masked = x_validation.copy()
        masked.loc[observed.index, field] = np.nan
        predicted = imputer.transform(masked)[:, FEATURES.index(field)]
        imputation_reference[field] = {
            'validation_observed_n': int(len(observed)),
            'train_median': median,
            'median_absolute_error_on_observed_validation': float((observed - median).abs().mean()),
            'chosen_imputation_absolute_error_on_observed_validation': float(
                (observed - predicted[observed.index]).abs().mean()),
            'method': 'KNN_7' if field == 'resp_rate_mean' else 'train_median',
        }

    metrics = {
        'model_version': 'numeric5-future-aggregate-v2-grid',
        'source': 'PhysioNet 2012 Set A, age >=60; derived sjj auxiliary splits',
        'target': 'Future 24-48h day-level median vital scores sum to >=5, with >=3 measured components and a label certain despite missing components',
        'limitations': [
            'This is a five-component day-level proxy, not same-time or full NEWS2; oxygen and consciousness are excluded.',
            'Only certain labels are retained; metrics apply to that selected subset, not all patients.',
            'Not a healthy/unhealthy diagnosis or a validated community risk outcome.',
            'Training patients came from an ICU; community performance is unknown.',
            'Respiratory rate and SpO2 are often missing; imputation cannot recover true readings.',
        ],
        'features': FEATURES,
        'rows': {name: len(part) for name, part in (
            ('train', train), ('validation', validation), ('test', test))},
        'positive_count': {name: int(part[TARGET].sum()) for name, part in (
            ('train', train), ('validation', validation), ('test', test))},
        'positive_rate': {name: float(part[TARGET].mean()) for name, part in (
            ('train', train), ('validation', validation), ('test', test))},
        'train_missing_rate': {field: float(x_train[field].isna().mean()) for field in FEATURES},
        'train_imputation_medians': {field: float(value) for field, value in zip(FEATURES, imputer.statistics_)},
        'imputation_reference': imputation_reference,
        'parameter_search': {
            'method': '3-fold stratified grid search on training patients',
            'selection_metric': 'mean cross-validation average precision (PR-AUC)',
            'candidate_count': len(search_results),
            'grid': PARAMETER_GRID,
            'fixed_parameters': FIXED_PARAMETERS,
            'selected_parameters': selected_parameters,
            'best_cv_result': cv_winner,
            'full_results_file': 'grid_search_results.csv',
        },
        'threshold': threshold,
        'validation_metrics': report(y_validation, validation_probability, threshold),
        'test_metrics': report(y_test, test_probability, threshold),
        'best_iteration': int(model.best_iteration),
    }
    bundle = {
        'model_name': 'XGBoost', 'model_version': metrics['model_version'],
        'features': FEATURES, 'imputer': imputer, 'model': model,
        'threshold': threshold, 'target_description': metrics['target'],
        'training_basis': 'NUMERIC5_FUTURE_AGGREGATE_V1',
    }
    OUTPUT.mkdir(parents=True, exist_ok=True)
    joblib.dump(bundle, OUTPUT / 'five_score_future_model.joblib')
    (OUTPUT / 'metrics.json').write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding='utf-8')
    pd.DataFrame(search_results).sort_values(
        ['cv_pr_auc_mean', 'cv_roc_auc_mean'], ascending=False,
    ).to_csv(OUTPUT / 'grid_search_results.csv', index=False, encoding='utf-8-sig')
    print(json.dumps({
        'rows': metrics['rows'], 'selected_parameters': selected_parameters,
        'cv_pr_auc': cv_winner['cv_pr_auc_mean'],
        'best_rounds': metrics['best_iteration'] + 1,
        'threshold': threshold,
        'validation': metrics['validation_metrics'], 'test': metrics['test_metrics'],
        'output': str(OUTPUT),
    }, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
