-- Additive migration; select the intended business database before executing.
-- No seed users, measurements or predictions are inserted.
CREATE TABLE IF NOT EXISTS elder_disease_profile (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id INT NOT NULL,
 age INT NULL,
 sex_male TINYINT NULL,
 weight_kg DECIMAL(8,3) NULL,
 self_rated_health TINYINT NULL,
 smoking_current TINYINT NULL,
 drinking_any TINYINT NULL,
 exercise_regular TINYINT NULL,
 high_cholesterol_history TINYINT NULL,
 health_worsened TINYINT NULL,
 weight_gain_10lb TINYINT NULL,
 weight_loss_10lb TINYINT NULL,
 hip_fracture_history TINYINT NULL,
 profile_revision BIGINT NOT NULL,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_disease_profile_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS elder_disease_history (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id INT NOT NULL,
 disease_code VARCHAR(32) NOT NULL,
 history_status TINYINT NULL,
 diagnosed_year INT NULL,
 source VARCHAR(32) NOT NULL DEFAULT 'self_reported',
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_disease_history_user_code (user_id,disease_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS disease_prediction_assessment (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 user_id INT NOT NULL,
 request_id VARCHAR(36) NOT NULL,
 profile_revision BIGINT NOT NULL,
 input_snapshot_json LONGTEXT NOT NULL,
 input_sha256 CHAR(64) NOT NULL,
 model_summary_json LONGTEXT NOT NULL,
 model_version VARCHAR(80) NOT NULL,
 research_only TINYINT NOT NULL,
 validation_status VARCHAR(100) NOT NULL,
 assessment_status VARCHAR(32) NOT NULL,
 assessed_at TIMESTAMP NOT NULL,
 UNIQUE KEY uk_disease_assessment_request (user_id,request_id),
 KEY idx_disease_assessment_user_time (user_id,assessed_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS disease_prediction_result (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 assessment_id VARCHAR(36) NOT NULL,
 disease_code VARCHAR(32) NOT NULL,
 disease_name VARCHAR(80) NOT NULL,
 status VARCHAR(40) NOT NULL,
 probability DECIMAL(12,10) NULL,
 threshold DECIMAL(12,10) NULL,
 threshold_policy VARCHAR(40) NULL,
 alert TINYINT NULL,
 risk_band VARCHAR(20) NULL,
 reason VARCHAR(1000) NULL,
 missing_fields_json LONGTEXT NOT NULL,
 outcome_definition VARCHAR(500) NULL,
 horizon_definition VARCHAR(500) NULL,
 UNIQUE KEY uk_disease_result_assessment_code (assessment_id,disease_code),
 CONSTRAINT fk_disease_result_assessment FOREIGN KEY (assessment_id) REFERENCES disease_prediction_assessment(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
