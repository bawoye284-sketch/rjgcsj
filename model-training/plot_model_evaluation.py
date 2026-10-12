"""Create reproducible evaluation figures for the deployed five-score model."""

from pathlib import Path
import json

import joblib
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
from sklearn.metrics import (
    ConfusionMatrixDisplay, accuracy_score,
    confusion_matrix, roc_auc_score, roc_curve,
)
from sklearn.model_selection import StratifiedShuffleSplit
from xgboost import DMatrix, XGBClassifier

from train_five_score_model import FEATURES, OUTPUT, read_split
from feature_imputer import VitalFeatureImputer

FIGURES = OUTPUT / 'figures'
MODEL_FILE = OUTPUT / 'five_score_future_model.joblib'
METRICS_FILE = OUTPUT / 'metrics.json'


def setup_style():
    plt.rcParams.update({
        'font.family': 'Microsoft YaHei',
        'axes.unicode_minus': False,
        'figure.facecolor': 'white',
        'axes.spines.top': False,
        'axes.spines.right': False,
        'font.size': 11,
        'savefig.dpi': 200,
    })


def save(fig, name):
    FIGURES.mkdir(parents=True, exist_ok=True)
    fig.savefig(FIGURES / f'{name}.png', bbox_inches='tight')
    fig.savefig(FIGURES / f'{name}.pdf', bbox_inches='tight')
    plt.close(fig)


def plot_confusion(y_test, probability, threshold, expected):
    predicted = (probability >= threshold).astype(int)
    matrix = confusion_matrix(y_test, predicted, labels=[0, 1])
    if matrix.tolist() != expected:
        raise ValueError('Saved model and recorded test confusion matrix do not match')
    fig, ax = plt.subplots(figsize=(6.3, 5.3))
    display = ConfusionMatrixDisplay(matrix, display_labels=['未达到预警', '达到预警'])
    display.plot(ax=ax, cmap='Blues', colorbar=False, values_format='d')
    ax.set_title('五项综合分模型：测试集混淆矩阵', weight='bold', pad=18)
    ax.set_xlabel('模型预测')
    ax.set_ylabel('后续实测标签')
    ax.text(0.5, -0.19, f'测试集 n={len(y_test)}  ·  阈值={threshold:.3f}  ·  正确率={accuracy_score(y_test, predicted):.1%}',
            transform=ax.transAxes, ha='center', va='top', color='#526277')
    save(fig, 'confusion_matrix_test')


def plot_roc(y_test, probability, expected_auc):
    fpr, tpr, _ = roc_curve(y_test, probability)
    auc = roc_auc_score(y_test, probability)
    if not np.isclose(auc, expected_auc, atol=1e-9):
        raise ValueError('Saved model and recorded test ROC AUC do not match')
    fig, ax = plt.subplots(figsize=(6.8, 5.3))
    ax.plot(fpr, tpr, lw=2.8, color='#1769aa', label=f'测试集 ROC  ·  AUC = {auc:.3f}')
    ax.plot([0, 1], [0, 1], '--', color='#9ca8b5', lw=1.4, label='随机判断')
    ax.set(xlim=(0, 1), ylim=(0, 1.02), xlabel='假阳性率', ylabel='真正率')
    ax.set_title('五项综合分模型：测试集 ROC 曲线', weight='bold', pad=18)
    ax.grid(alpha=0.18)
    ax.legend(frameon=False, loc='lower right')
    ax.text(0.02, -0.19, f'测试集 n={len(y_test)}；其中预警标签 {int(y_test.sum())} 人',
            transform=ax.transAxes, va='top', color='#526277')
    save(fig, 'roc_auc_test')


def plot_learning_curve(bundle, train, validation):
    """Show both boosting progress and sample-size learning behaviour."""
    model = bundle['model']
    imputer = bundle['imputer']
    x_train = train[FEATURES].astype(float)
    x_validation = validation[FEATURES].astype(float)
    y_train = train['future_five_score_warning'].astype(int).to_numpy()
    y_validation = validation['future_five_score_warning'].astype(int).to_numpy()
    a = imputer.transform(x_train)
    b = imputer.transform(x_validation)
    booster = model.get_booster()
    rounds = np.arange(1, booster.num_boosted_rounds() + 1)
    train_auc = []
    validation_auc = []
    for count in rounds:
        train_probability = booster.predict(DMatrix(a), iteration_range=(0, int(count)))
        validation_probability = booster.predict(DMatrix(b), iteration_range=(0, int(count)))
        train_auc.append(roc_auc_score(y_train, train_probability))
        validation_auc.append(roc_auc_score(y_validation, validation_probability))

    sizes = [100, 170, 250, 340, len(train)]
    train_by_size = []
    validation_by_size = []
    params = model.get_params().copy()
    params['early_stopping_rounds'] = None
    params['n_estimators'] = model.best_iteration + 1
    for size in sizes:
        train_scores, validation_scores = [], []
        if size == len(train):
            subsets = [np.arange(len(train))]
        else:
            splitter = StratifiedShuffleSplit(n_splits=3, train_size=size, random_state=42 + size)
            subsets = [indices for indices, _ in splitter.split(x_train, y_train)]
        for subset_indices in subsets:
            subset_x = x_train.iloc[subset_indices]
            subset_y = y_train[subset_indices]
            subset_imputer = VitalFeatureImputer(FEATURES).fit(subset_x)
            subset_a = subset_imputer.transform(subset_x)
            subset_b = subset_imputer.transform(x_validation)
            params['scale_pos_weight'] = float((subset_y == 0).sum() / (subset_y == 1).sum())
            candidate = XGBClassifier(**params)
            candidate.fit(subset_a, subset_y, verbose=False)
            train_scores.append(roc_auc_score(subset_y, candidate.predict_proba(subset_a)[:, 1]))
            validation_scores.append(roc_auc_score(y_validation, candidate.predict_proba(subset_b)[:, 1]))
        train_by_size.append(train_scores)
        validation_by_size.append(validation_scores)

    fig, axes = plt.subplots(1, 2, figsize=(12.8, 4.9))
    axes[0].plot(rounds, train_auc, label='训练集', color='#1769aa', lw=2)
    axes[0].plot(rounds, validation_auc, label='验证集', color='#df7b28', lw=2)
    axes[0].axvline(model.best_iteration + 1, ls='--', lw=1.5, color='#586779',
                    label=f'最佳轮次 {model.best_iteration + 1}')
    axes[0].set(title='迭代过程', xlabel='提升轮次', ylabel='ROC AUC', ylim=(0.5, 1.02))
    axes[0].legend(frameon=False, loc='lower right')

    for values, label, color in ((train_by_size, '训练集', '#1769aa'),
                                 (validation_by_size, '验证集', '#df7b28')):
        means = np.array([np.mean(item) for item in values])
        stds = np.array([np.std(item) for item in values])
        axes[1].plot(sizes, means, marker='o', lw=2, label=label, color=color)
        axes[1].fill_between(sizes, means - stds, means + stds, color=color, alpha=0.13)
    axes[1].set(title='训练人数变化（每点重复 3 次）', xlabel='训练人数', ylabel='ROC AUC',
                xlim=(85, len(train) + 15), ylim=(0.5, 1.02))
    axes[1].legend(frameon=False, loc='lower right')
    for ax in axes:
        ax.grid(alpha=0.18)
    fig.suptitle('五项综合分模型：学习曲线', fontsize=15, weight='bold', y=1.03)
    fig.text(0.5, -0.02, '参数矩阵仅在训练集内搜索；验证集用于最终早停与阈值，独立结果见测试集 ROC。',
             ha='center', color='#526277')
    fig.tight_layout()
    save(fig, 'learning_curve')


def main():
    setup_style()
    bundle = joblib.load(MODEL_FILE)
    metrics = json.loads(METRICS_FILE.read_text(encoding='utf-8'))
    if bundle['model_version'] != metrics['model_version']:
        raise ValueError('Model and metrics versions differ')
    train, validation, test = (read_split(name) for name in ('train', 'validation', 'test'))
    x_test = test[FEATURES].astype(float)
    y_test = test['future_five_score_warning'].astype(int).to_numpy()
    probability = bundle['model'].predict_proba(bundle['imputer'].transform(x_test))[:, 1]
    plot_confusion(y_test, probability, bundle['threshold'], metrics['test_metrics']['confusion_matrix'])
    plot_roc(y_test, probability, metrics['test_metrics']['roc_auc'])
    plot_learning_curve(bundle, train, validation)
    print(f'已生成图表：{FIGURES}')


if __name__ == '__main__':
    main()
