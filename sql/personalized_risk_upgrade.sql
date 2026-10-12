-- ============================================================
-- 五项生理指标模型 + 个人历史变化数据库升级脚本
-- 执行前请先备份 personal_health 数据库
-- ============================================================

CREATE TABLE IF NOT EXISTS `elder_risk_profile` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` INT NOT NULL,
  `age` INT NOT NULL COMMENT '年龄',
  `sex_male` TINYINT NOT NULL DEFAULT 0 COMMENT '1男0女',
  `bmi` DECIMAL(6,2) NOT NULL,
  `heart_disease` TINYINT NOT NULL DEFAULT 0 COMMENT '冠心病/心肌梗死相关病史',
  `smoking_current` TINYINT NOT NULL DEFAULT 0 COMMENT '当前吸烟',
  `drinking_any` TINYINT NOT NULL DEFAULT 0 COMMENT '是否饮酒',
  `drinks_per_week` DECIMAL(8,2) NOT NULL DEFAULT 0 COMMENT '每周饮酒量',
  `physical_activity` TINYINT NOT NULL DEFAULT 0 COMMENT '是否有休闲身体活动',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_elder_risk_profile_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='老人风险评估基础档案';

CREATE TABLE IF NOT EXISTS `elder_vital_measurement` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` INT NOT NULL,
  `systolic_pressure` DECIMAL(8,2) NULL COMMENT '收缩压',
  `diastolic_pressure` DECIMAL(8,2) NULL COMMENT '舒张压',
  `heart_rate` DECIMAL(8,2) NULL COMMENT '心率',
  `spo2` DECIMAL(8,2) NULL COMMENT '血氧',
  `temperature` DECIMAL(8,2) NULL COMMENT '体温',
  `respiratory_rate` DECIMAL(8,2) NULL COMMENT '呼吸频率',
  `supplemental_oxygen` TINYINT NULL COMMENT '本次是否吸氧',
  `consciousness` VARCHAR(24) NULL COMMENT '本次意识状态或新发意识改变',
  `measured_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_elder_vital_user_time` (`user_id`,`measured_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='老人连续生命体征记录，用于个人历史校准';

CREATE TABLE IF NOT EXISTS `personalized_risk_assessment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` INT NOT NULL,
  `group_risk_probability` DECIMAL(8,6) NOT NULL COMMENT '五项指标模型预警值，列名为兼容旧记录保留',
  `personal_change_score` DECIMAL(8,6) NOT NULL DEFAULT 0 COMMENT '个人历史变化程度',
  `baseline_available` TINYINT NOT NULL DEFAULT 0 COMMENT '个人基线是否可用',
  `risk_level` VARCHAR(20) NOT NULL COMMENT '正常/关注/高风险',
  `reasons_json` TEXT NULL COMMENT '主要原因JSON',
  `single_alerts_json` TEXT NULL COMMENT '实测单项警报JSON',
  `news2_complete` TINYINT NOT NULL DEFAULT 0 COMMENT '本次五项数值指标是否完整',
  `assessment_basis` VARCHAR(32) NOT NULL DEFAULT 'LEGACY' COMMENT '评估依据',
  `model_name` VARCHAR(64) NULL,
  `model_version` VARCHAR(64) NULL,
  `assessment_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_personalized_risk_user_time` (`user_id`,`assessment_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个性化健康风险评估结果';
