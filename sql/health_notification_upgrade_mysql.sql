-- Phase 3, additive/repeatable. No contact data or secrets are seeded.
CREATE TABLE IF NOT EXISTS health_emergency_contact (
 id BIGINT NOT NULL AUTO_INCREMENT,
 user_id INT NOT NULL,
 name VARCHAR(60) NOT NULL,
 relationship VARCHAR(40) NOT NULL,
 email VARCHAR(254) NOT NULL,
 phone VARCHAR(32) NULL,
 authorized TINYINT NOT NULL DEFAULT 0,
 email_verification VARCHAR(8) NOT NULL DEFAULT 'NONE',
 phone_verification VARCHAR(8) NOT NULL DEFAULT 'NONE',
 revision INT NOT NULL DEFAULT 1,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (id),
 KEY idx_contact_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS health_contact_verification (
 contact_id BIGINT NOT NULL,
 token_hash VARCHAR(64) NOT NULL,
 verification_kind VARCHAR(8) NOT NULL,
 contact_revision INT NOT NULL,
 expires_at DATETIME NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 PRIMARY KEY (contact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS health_doctor_authorization (
 user_id INT NOT NULL,
 doctor_id INT NOT NULL,
 authorized TINYINT NOT NULL DEFAULT 0,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (user_id,doctor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS health_notification_checkpoint (
 measurement_id BIGINT NOT NULL,
 status VARCHAR(12) NOT NULL DEFAULT 'CAPTURED',
 error_code VARCHAR(64) NULL,
 attempts INT NOT NULL DEFAULT 0,
 captured_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (measurement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS health_notification_task (
 id BIGINT NOT NULL AUTO_INCREMENT,
 user_id INT NOT NULL,
 event_id BIGINT NOT NULL,
 measurement_id BIGINT NOT NULL,
 recipient_type VARCHAR(12) NOT NULL,
 recipient_id BIGINT NOT NULL,
 recipient_revision INT NULL,
 channel VARCHAR(8) NOT NULL,
 phase VARCHAR(16) NOT NULL,
 priority INT NOT NULL DEFAULT 0,
 status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 error_code VARCHAR(64) NULL,
 retry_at DATETIME NULL,
 lease_until DATETIME NULL,
 sent_at DATETIME NULL,
 read_at DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (id),
 UNIQUE KEY uk_health_notice_stage (event_id,recipient_type,recipient_id,channel,phase),
 KEY idx_health_notice_owner (user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS health_notification_attempt (
 task_id BIGINT NOT NULL,
 attempt_no INT NOT NULL,
 status VARCHAR(12) NOT NULL,
 error_code VARCHAR(64) NULL,
 started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 finished_at DATETIME NULL,
 PRIMARY KEY (task_id,attempt_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS health_alert_action (
 id BIGINT NOT NULL AUTO_INCREMENT,
 event_id BIGINT NOT NULL,
 doctor_id INT NOT NULL,
 action VARCHAR(16) NOT NULL,
 phase VARCHAR(16) NOT NULL,
 note VARCHAR(1000) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (id),
 KEY idx_health_action_event (event_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
