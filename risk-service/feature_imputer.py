"""Training-only fitted imputation shared by offline training and inference.

No imputed value is written back as a patient's measured observation.
"""

import numpy as np
from sklearn.impute import KNNImputer, SimpleImputer
from sklearn.preprocessing import StandardScaler


class VitalFeatureImputer:
    def __init__(self, features):
        self.features = list(features)
        self.rr_index = self.features.index('resp_rate_mean')

    def fit(self, frame):
        values = np.asarray(frame[self.features], dtype=float)
        self.median_imputer = SimpleImputer(
            strategy='median', add_indicator=True, keep_empty_features=True).fit(values)
        self.scaler = StandardScaler().fit(values)
        self.knn = KNNImputer(n_neighbors=7, weights='distance').fit(self.scaler.transform(values))
        self.statistics_ = self.median_imputer.statistics_
        return self

    def transform(self, frame):
        values = np.asarray(frame[self.features], dtype=float)
        output = self.median_imputer.transform(values)
        missing_rr = np.isnan(values[:, self.rr_index])
        if np.any(missing_rr):
            scaled = self.scaler.transform(values[missing_rr])
            rr_scaled = self.knn.transform(scaled)[:, self.rr_index]
            rr = rr_scaled * self.scaler.scale_[self.rr_index] + self.scaler.mean_[self.rr_index]
            output[missing_rr, self.rr_index] = np.clip(rr, 1, 80)
        return output
