-- 仅供已有 MySQL 数据库升级时执行一次；执行前先备份数据库。
-- 旧测量记录缺少的指标保持 NULL，不能补造为真实测量值。
ALTER TABLE `elder_vital_measurement`
  ADD COLUMN `respiratory_rate` DECIMAL(8,2) NULL COMMENT '呼吸频率',
  ADD COLUMN `supplemental_oxygen` TINYINT NULL COMMENT '本次是否吸氧',
  ADD COLUMN `consciousness` VARCHAR(24) NULL COMMENT '本次意识状态或新发意识改变';

ALTER TABLE `personalized_risk_assessment`
  ADD COLUMN `single_alerts_json` TEXT NULL COMMENT '实测单项警报JSON',
  ADD COLUMN `news2_complete` TINYINT NOT NULL DEFAULT 0 COMMENT '本次五项数值指标是否完整',
  ADD COLUMN `assessment_basis` VARCHAR(32) NOT NULL DEFAULT 'LEGACY' COMMENT '评估依据';
