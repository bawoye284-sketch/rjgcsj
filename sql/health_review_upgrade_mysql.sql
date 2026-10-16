-- Select the existing business database. Additive and safe to execute again.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='personalized_risk_assessment' AND column_name='request_id')=0,
 'ALTER TABLE personalized_risk_assessment ADD COLUMN request_id VARCHAR(36) NULL', 'SELECT 1');
PREPARE health_review_upgrade FROM @ddl; EXECUTE health_review_upgrade; DEALLOCATE PREPARE health_review_upgrade;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='personalized_risk_assessment' AND column_name='input_snapshot_json')=0,
 'ALTER TABLE personalized_risk_assessment ADD COLUMN input_snapshot_json LONGTEXT NULL', 'SELECT 1');
PREPARE health_review_upgrade FROM @ddl; EXECUTE health_review_upgrade; DEALLOCATE PREPARE health_review_upgrade;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='personalized_risk_assessment' AND index_name='uk_personal_assessment_request')=0,
 'CREATE UNIQUE INDEX uk_personal_assessment_request ON personalized_risk_assessment(user_id,request_id)', 'SELECT 1');
PREPARE health_review_upgrade FROM @ddl; EXECUTE health_review_upgrade; DEALLOCATE PREPARE health_review_upgrade;
