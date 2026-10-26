-- Phase 2 only. Additive and repeatable; no existing measurements are re-evaluated.
CREATE TABLE IF NOT EXISTS health_alert_rule (
  metric_key VARCHAR(32) NOT NULL,
  config_id INT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  consecutive_count INT NOT NULL DEFAULT 3,
  window_days INT NOT NULL DEFAULT 7,
  change_delta DECIMAL(10,2) NULL,
  baseline_count INT NOT NULL DEFAULT 3,
  revision INT NOT NULL DEFAULT 1,
  PRIMARY KEY (metric_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS health_alert_evaluation (
  measurement_id BIGINT NOT NULL,
  user_id INT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  result_json LONGTEXT NULL,
  error_message VARCHAR(255) NULL,
  retry_at DATETIME NULL,
  evaluated_at DATETIME NULL,
  PRIMARY KEY (measurement_id),
  KEY idx_alert_evaluation_user (user_id,measurement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS health_alert_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id INT NOT NULL,
  metric_key VARCHAR(32) NOT NULL,
  level VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  first_measurement_id BIGINT NOT NULL,
  last_measurement_id BIGINT NOT NULL,
  occurrences INT NOT NULL DEFAULT 1,
  details_json LONGTEXT NOT NULL,
  opened_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  recovered_at DATETIME NULL,
  PRIMARY KEY (id),
  KEY idx_alert_event_user (user_id,status,metric_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Stable numeric field keys; bind only unambiguous existing GLOBAL symbols.
-- Missing mappings remain visible as UNCONFIGURED. Never invent medical ranges.
INSERT INTO health_alert_rule (metric_key,config_id)
SELECT 'systolic_pressure',NULL WHERE NOT EXISTS (SELECT 1 FROM health_alert_rule WHERE metric_key='systolic_pressure');
INSERT INTO health_alert_rule (metric_key,config_id)
SELECT 'diastolic_pressure',NULL WHERE NOT EXISTS (SELECT 1 FROM health_alert_rule WHERE metric_key='diastolic_pressure');
INSERT INTO health_alert_rule (metric_key,config_id)
SELECT 'heart_rate',NULL WHERE NOT EXISTS (SELECT 1 FROM health_alert_rule WHERE metric_key='heart_rate');
INSERT INTO health_alert_rule (metric_key,config_id)
SELECT 'spo2',NULL WHERE NOT EXISTS (SELECT 1 FROM health_alert_rule WHERE metric_key='spo2');
INSERT INTO health_alert_rule (metric_key,config_id)
SELECT 'temperature',NULL WHERE NOT EXISTS (SELECT 1 FROM health_alert_rule WHERE metric_key='temperature');
INSERT INTO health_alert_rule (metric_key,config_id)
SELECT 'respiratory_rate',NULL WHERE NOT EXISTS (SELECT 1 FROM health_alert_rule WHERE metric_key='respiratory_rate');
