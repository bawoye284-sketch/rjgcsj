"""Development-only nested selection and transparent operating-point tradeoffs.

The old evaluation results have been inspected. Reusing those participants is
an exploratory comparison, even though this code never tunes on their labels.
"""

from itertools import product

import numpy as np
from sklearn.calibration import CalibratedClassifierCV
from sklearn.compose import ColumnTransformer
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (average_precision_score, brier_score_loss, roc_auc_score,
                             roc_curve)
from sklearn.model_selection import GridSearchCV, StratifiedKFold
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from xgboost import XGBClassifier

from common import ROOT, SEED, read_json

RECIPE_PATH = ROOT / "config" / "training_optimized.json"


def operating_thresholds(y, probability):
    """All thresholds come from development OOF predictions, never holdout."""
    fpr, tpr, thresholds = roc_curve(y, probability, drop_intermediate=False)
    # sklearn's first threshold is infinity. A finite value above the maximum
    # represents the same all-negative decision and can be archived as JSON.
    thresholds[0] = np.nextafter(float(np.max(probability)), np.inf)
    positives, negatives = int(np.sum(y)), len(y) - int(np.sum(y))
    tp, fp = tpr * positives, fpr * negatives
    f1 = np.divide(2 * tp, positives + tp + fp,
                   out=np.zeros_like(tp), where=(positives + tp + fp) > 0)
    youden = tpr - fpr
    finite = range(len(thresholds))
    rules = {
        "specificity80": [i for i in finite if fpr[i] <= .20 + 1e-12],
        "balanced_youden": list(finite),
        "screening70": [i for i in finite if tpr[i] >= .70 - 1e-12],
        "f1": list(finite),
    }
    result = {}
    for policy, eligible in rules.items():
        def objective(i):
            primary = -fpr[i] if policy == "screening70" else f1[i] if policy == "f1" else youden[i]
            return (round(float(primary), 12), -fpr[i], thresholds[i])
        index = max(eligible, key=objective)
        result[policy] = float(thresholds[index])
    return result


def search_grid(family, code, recipe):
    grids = []
    for feature_set, names in recipe["feature_sets"].items():
        features = [name for name in names if name != f"history_{code}"]
        if family == "LogisticRegression":
            candidates = [{"C": c, "class_weight": weight}
                          for c, weight in product(recipe["logistic_C"], recipe["logistic_class_weight"])]
        else:
            candidates = [{**params, "scale_pos_weight": weight}
                          for params, weight in product(recipe["xgboost_candidates"], recipe["xgboost_positive_weight"])]
        for parameters in candidates:
            grids.append({"columns__transformers": [[("selected", "passthrough", features)]],
                          **{f"model__{key}": [value] for key, value in parameters.items()}})
    return grids


def base_pipeline(family):
    if family == "LogisticRegression":
        model = LogisticRegression(max_iter=3000, random_state=SEED)
    else:
        model = XGBClassifier(objective="binary:logistic", tree_method="hist",
                              n_jobs=1, random_state=SEED, eval_metric="logloss",
                              subsample=1., colsample_bytree=1.)
    return Pipeline([
        ("columns", ColumnTransformer([("selected", "passthrough", [])], remainder="drop",
                                       verbose_feature_names_out=False)),
        ("imputer", SimpleImputer(strategy="median", add_indicator=True, keep_empty_features=True)),
        ("scale", StandardScaler()), ("model", model)])


def fit_family(family, code, x, y, participant_ids, recipe):
    """Outer validation excludes feature search, fitting AND calibration."""
    def choose(results):
        return max(range(len(results["params"])), key=lambda i: (
            results["mean_test_auc"][i], results["mean_test_ap"][i],
            -len(results["params"][i]["columns__transformers"][0][2])))

    def search(local_x, local_y, fold_seed):
        cv = StratifiedKFold(recipe["inner_folds"], shuffle=True, random_state=fold_seed)
        fitted = GridSearchCV(base_pipeline(family), search_grid(family, code, recipe),
                              scoring={"auc": "roc_auc", "ap": "average_precision"},
                              refit=choose, cv=cv, n_jobs=1, error_score="raise")
        fitted.fit(local_x, local_y)
        table = []
        for i, parameters in enumerate(fitted.cv_results_["params"]):
            table.append({"parameters": parameters,
                          "mean_roc_auc": float(fitted.cv_results_["mean_test_auc"][i]),
                          "std_roc_auc": float(fitted.cv_results_["std_test_auc"][i]),
                          "mean_average_precision": float(fitted.cv_results_["mean_test_ap"][i]),
                          "fold_roc_auc": [float(fitted.cv_results_[f"split{j}_test_auc"][i])
                                           for j in range(recipe["inner_folds"])]})
        return fitted.best_estimator_, fitted.best_params_, table

    def calibrated(model, seed):
        return CalibratedClassifierCV(model, method="sigmoid", ensemble=False,
                                       cv=StratifiedKFold(3, shuffle=True, random_state=seed), n_jobs=1)

    outer = StratifiedKFold(recipe["outer_folds"], shuffle=True, random_state=SEED)
    oof = np.full(len(y), np.nan)
    fold_log = []
    for fold, (train, validation) in enumerate(outer.split(x, y)):
        selected, params, candidates = search(x.iloc[train], y[train], SEED + 10 + fold)
        model = calibrated(selected, SEED + 100 + fold)
        model.fit(x.iloc[train], y[train])
        oof[validation] = model.predict_proba(x.iloc[validation])[:, 1]
        fold_log.append({"outer_fold": fold, "parameters": params, "search": candidates,
                         "training_participant_ids": [participant_ids[i] for i in train],
                         "validation_participant_ids": [participant_ids[i] for i in validation],
                         "n_train": len(train), "events_train": int(y[train].sum()),
                         "n_validation": len(validation), "events_validation": int(y[validation].sum()),
                         "roc_auc": float(roc_auc_score(y[validation], oof[validation]))})
        print(f"  {code}/{family}: nested fold {fold + 1}/{recipe['outer_folds']} complete", flush=True)
    if not np.isfinite(oof).all():
        raise ValueError("Incomplete nested OOF predictions")
    selected, params, candidates = search(x, y, SEED + 20)
    model = calibrated(selected, SEED + 200)
    model.fit(x, y)
    thresholds = operating_thresholds(y, oof)
    info = {"parameters": params, "search": candidates,
            "calibration": recipe["calibration"], "feature_selection": "nested inner-fold search",
            "development_oof_roc_auc": float(roc_auc_score(y, oof)),
            "development_oof_average_precision": float(average_precision_score(y, oof)),
            "development_oof_brier": float(brier_score_loss(y, oof)),
            "threshold": thresholds[recipe["default_threshold_policy"]],
            "threshold_policy": recipe["default_threshold_policy"], "operating_thresholds": thresholds}
    return model, oof, info, fold_log
