-- Phase 4: preserve existing events and tasks. SOS has no measurement IDs.
ALTER TABLE health_alert_event MODIFY first_measurement_id BIGINT NULL;
ALTER TABLE health_alert_event MODIFY last_measurement_id BIGINT NULL;
ALTER TABLE health_notification_task MODIFY measurement_id BIGINT NULL;
SET @sos_priority_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='health_notification_task' AND column_name='priority')=0,
 'ALTER TABLE health_notification_task ADD COLUMN priority INT NOT NULL DEFAULT 0','SELECT 1');
PREPARE sos_priority_statement FROM @sos_priority_sql;
EXECUTE sos_priority_statement;
DEALLOCATE PREPARE sos_priority_statement;

CREATE TABLE IF NOT EXISTS health_sos_event (
 event_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
 confirmation_deadline DATETIME NOT NULL,
 next_check_at DATETIME NOT NULL,
 escalation_stage INT NOT NULL DEFAULT 0,
 planned_stage INT NOT NULL DEFAULT -1,
 notification_error VARCHAR(64) NULL,
 acknowledged_at DATETIME NULL,
 resolved_at DATETIME NULL,
 PRIMARY KEY (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS health_sos_reminder (
 id BIGINT NOT NULL AUTO_INCREMENT,
 user_id INT NOT NULL,
 event_id BIGINT NOT NULL,
 request_id VARCHAR(36) NOT NULL,
 ordinal INT NOT NULL,
 captured TINYINT NOT NULL DEFAULT 0,
 error_code VARCHAR(64) NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (id),
 UNIQUE KEY uk_sos_reminder_request (user_id,request_id),
 UNIQUE KEY uk_sos_reminder_ordinal (event_id,ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS health_sos_request (
 user_id INT NOT NULL,
 request_id VARCHAR(36) NOT NULL,
 event_id BIGINT NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (user_id,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
