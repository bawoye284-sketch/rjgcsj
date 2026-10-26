-- Local demonstration data. Password for each sample account is 123456,
-- double-MD5 encoded by the existing Vue login form. Do not use in production.
INSERT IGNORE INTO `user` (`id`,`user_account`,`user_name`,`user_pwd`,`user_email`,`user_role`,`is_login`,`is_word`,`create_time`) VALUES
  (1,'demo_admin','演示管理员','14e1b600b1fd579f47433b88e8d85291','admin@demo.local',1,0,0,CURRENT_TIMESTAMP),
  (2,'demo_elder','演示用户','14e1b600b1fd579f47433b88e8d85291','elder@demo.local',2,0,0,CURRENT_TIMESTAMP),
  (3,'demo_doctor_user','演示医生','14e1b600b1fd579f47433b88e8d85291','doctor@demo.local',2,0,0,CURRENT_TIMESTAMP),
  (1001,'demo_patient_liu','刘阿姨','14e1b600b1fd579f47433b88e8d85291','liu@demo.local',2,0,0,CURRENT_TIMESTAMP),
  (1002,'demo_patient_wang','王大爷','14e1b600b1fd579f47433b88e8d85291','wang@demo.local',2,0,0,CURRENT_TIMESTAMP),
  (1003,'demo_patient_zhang','张阿姨','14e1b600b1fd579f47433b88e8d85291','zhang@demo.local',2,0,0,CURRENT_TIMESTAMP);

INSERT IGNORE INTO `doctor` (`id`,`user_id`,`name`,`account`,`password`,`specialty`,`title`,`hospital`,`description`,`status`,`consultation_count`,`create_time`) VALUES
  (1,3,'李医生','demo_doctor','14e1b600b1fd579f47433b88e8d85291','心内科','主任医师','演示医院','提供心血管健康咨询的演示医生。',1,5,CURRENT_TIMESTAMP),
  (2,3,'王医生','demo_doctor2','14e1b600b1fd579f47433b88e8d85291','全科','主治医师','演示社区卫生中心','提供日常健康咨询的演示医生。',1,3,CURRENT_TIMESTAMP);

INSERT IGNORE INTO `tags` (`id`,`name`) VALUES (1,'健康管理'),(2,'营养饮食'),(3,'运动康复'),(4,'心理健康');
INSERT IGNORE INTO `news` (`id`,`name`,`content`,`tag_id`,`cover`,`reader_ids`,`is_top`,`create_time`) VALUES
  (1,'老年人日常健康监测要点','<p>建议定期记录血压、心率、体重，并关注长期变化。</p>',1,'/img/demo-health.svg','2',1,CURRENT_TIMESTAMP),
  (2,'均衡饮食与规律作息','<p>保持多样化饮食，合理安排休息时间。</p>',2,'/img/demo-food.svg',NULL,1,CURRENT_TIMESTAMP),
  (3,'适合老年人的轻量运动','<p>可根据身体情况选择散步等活动，并循序渐进。</p>',3,'/img/demo-exercise.svg',NULL,0,CURRENT_TIMESTAMP),
  (4,'关注情绪与社会联系','<p>保持交流与兴趣活动，有助于维护身心健康。</p>',4,'/img/demo-social.svg',NULL,0,CURRENT_TIMESTAMP);
-- Earlier demo databases already contain these rows with empty covers.
-- Fill only empty values so uploaded replacements remain untouched.
UPDATE `news` SET `cover`='/img/demo-health.svg' WHERE `id`=1 AND (`cover` IS NULL OR `cover`='');
UPDATE `news` SET `cover`='/img/demo-food.svg' WHERE `id`=2 AND (`cover` IS NULL OR `cover`='');
UPDATE `news` SET `cover`='/img/demo-exercise.svg' WHERE `id`=3 AND (`cover` IS NULL OR `cover`='');
UPDATE `news` SET `cover`='/img/demo-social.svg' WHERE `id`=4 AND (`cover` IS NULL OR `cover`='');
INSERT IGNORE INTO `news_save` (`id`,`user_id`,`news_id`,`create_time`) VALUES (1,2,1,CURRENT_TIMESTAMP);
INSERT IGNORE INTO `evaluations` (`id`,`parent_id`,`commenter_id`,`replier_id`,`content_type`,`content_id`,`content`,`upvote_list`,`create_time`) VALUES
  (1,NULL,2,NULL,'NEWS',1,'记录数据后更容易看到变化。',NULL,CURRENT_TIMESTAMP),
  (2,1,3,2,'NEWS',1,'持续记录确实有帮助。',NULL,CURRENT_TIMESTAMP);
INSERT IGNORE INTO `message` (`id`,`content`,`message_type`,`receiver_id`,`sender_id`,`is_read`,`content_id`,`create_time`) VALUES
  (1,'欢迎使用演示模式',3,2,1,0,NULL,CURRENT_TIMESTAMP),
  (2,'您的健康数据可以在个人中心查看',3,2,1,0,NULL,CURRENT_TIMESTAMP);

INSERT IGNORE INTO `health_model_config` (`id`,`user_id`,`name`,`detail`,`unit`,`symbol`,`is_global`,`value_range`) VALUES
  (1,1,'体重','体重记录','kg','Weight',1,'40,100'),
  (2,1,'心率','静息心率记录','次/分','Bpm',1,'60,100'),
  (3,1,'收缩压','血压高压记录','mmHg','SYS',1,'90,140'),
  (4,1,'舒张压','血压低压记录','mmHg','DIA',1,'60,90'),
  (5,1,'血氧','血氧饱和度记录','%','SpO2',1,'95,100');
INSERT IGNORE INTO `user_health` (`id`,`user_id`,`health_model_config_id`,`value`,`create_time`) VALUES
  (1,2,1,'68.5',DATEADD('DAY',-6,CURRENT_TIMESTAMP)),
  (2,2,1,'68.2',DATEADD('DAY',-3,CURRENT_TIMESTAMP)),
  (3,2,1,'68.0',CURRENT_TIMESTAMP),
  (4,2,2,'74',DATEADD('DAY',-6,CURRENT_TIMESTAMP)),
  (5,2,2,'76',DATEADD('DAY',-3,CURRENT_TIMESTAMP)),
  (6,2,2,'78',CURRENT_TIMESTAMP),
  (7,2,3,'128',CURRENT_TIMESTAMP),
  (8,2,4,'78',CURRENT_TIMESTAMP),
  (9,2,5,'98',CURRENT_TIMESTAMP),
  (10001,2,1,'68.9',DATEADD('DAY',-9,CURRENT_TIMESTAMP)),
  (10002,2,2,'73',DATEADD('DAY',-9,CURRENT_TIMESTAMP)),
  (10003,2,3,'132',DATEADD('DAY',-9,CURRENT_TIMESTAMP)),
  (10004,2,3,'130',DATEADD('DAY',-6,CURRENT_TIMESTAMP)),
  (10005,2,3,'129',DATEADD('DAY',-3,CURRENT_TIMESTAMP)),
  (10006,2,4,'82',DATEADD('DAY',-9,CURRENT_TIMESTAMP)),
  (10007,2,4,'80',DATEADD('DAY',-6,CURRENT_TIMESTAMP)),
  (10008,2,4,'79',DATEADD('DAY',-3,CURRENT_TIMESTAMP)),
  (10009,2,5,'96',DATEADD('DAY',-9,CURRENT_TIMESTAMP)),
  (10010,2,5,'97',DATEADD('DAY',-6,CURRENT_TIMESTAMP)),
  (10011,2,5,'98',DATEADD('DAY',-3,CURRENT_TIMESTAMP)),
  (10101,1001,1,'62.4',DATEADD('DAY',-9,CURRENT_TIMESTAMP)),
  (10102,1001,1,'62.2',DATEADD('DAY',-6,CURRENT_TIMESTAMP)),
  (10103,1001,1,'62.1',DATEADD('DAY',-3,CURRENT_TIMESTAMP)),
  (10104,1001,1,'62.0',CURRENT_TIMESTAMP),
  (10105,1001,2,'68',DATEADD('DAY',-9,CURRENT_TIMESTAMP)),
  (10106,1001,2,'70',DATEADD('DAY',-6,CURRENT_TIMESTAMP)),
  (10107,1001,2,'69',DATEADD('DAY',-3,CURRENT_TIMESTAMP)),
  (10108,1001,2,'71',CURRENT_TIMESTAMP),
  (10201,1002,3,'138',DATEADD('DAY',-9,CURRENT_TIMESTAMP)),
  (10202,1002,3,'140',DATEADD('DAY',-6,CURRENT_TIMESTAMP)),
  (10203,1002,3,'142',DATEADD('DAY',-3,CURRENT_TIMESTAMP)),
  (10204,1002,3,'141',CURRENT_TIMESTAMP),
  (10205,1002,4,'86',DATEADD('DAY',-9,CURRENT_TIMESTAMP)),
  (10206,1002,4,'87',DATEADD('DAY',-6,CURRENT_TIMESTAMP)),
  (10207,1002,4,'89',DATEADD('DAY',-3,CURRENT_TIMESTAMP)),
  (10208,1002,4,'88',CURRENT_TIMESTAMP),
  (10301,1003,5,'97',DATEADD('DAY',-9,CURRENT_TIMESTAMP)),
  (10302,1003,5,'98',DATEADD('DAY',-6,CURRENT_TIMESTAMP)),
  (10303,1003,5,'98',DATEADD('DAY',-3,CURRENT_TIMESTAMP)),
  (10304,1003,5,'99',CURRENT_TIMESTAMP);
INSERT IGNORE INTO `user_profile` (`id`,`user_id`,`health_status`,`risk_level`,`active_level`,`preference_tags`,`last_updated`) VALUES
  (1,2,'一般',2,3,'1,2,3',CURRENT_TIMESTAMP);
INSERT IGNORE INTO `health_advice` (`id`,`user_id`,`advice_type`,`content`,`source`,`priority`,`create_time`,`is_read`,`is_implemented`) VALUES
  (1,2,'生活方式','保持规律作息，继续记录血压和心率。','演示',2,CURRENT_TIMESTAMP,0,0),
  (2,2,'运动','根据身体情况安排适量步行。','演示',3,CURRENT_TIMESTAMP,0,0);
INSERT IGNORE INTO `health_prediction` (`id`,`user_id`,`metric_type`,`prediction_time`,`predicted_value`,`confidence`,`model_version`,`creation_time`) VALUES
  (1,2,2,DATEADD('DAY',1,CURRENT_TIMESTAMP),77.00,0.75,'demo',CURRENT_TIMESTAMP);
INSERT IGNORE INTO `health_risk_assessment` (`id`,`user_id`,`assessment_type`,`risk_level`,`risk_factors`,`assessment_result`,`assessment_time`) VALUES
  (1,2,'日常监测',2,'["血压波动"]','{"summary":"示例评估，请以实际测量为准"}',CURRENT_TIMESTAMP);
INSERT IGNORE INTO `food` (`id`,`name`,`tag_id`,`detail`,`create_time`) VALUES
  (1,'燕麦粥',2,'适量燕麦加水煮熟',CURRENT_TIMESTAMP);
INSERT IGNORE INTO `ai_model_log` (`id`,`model_name`,`api_endpoint`,`request_time`,`response_time`,`status`) VALUES
  (1,'演示模型','local',CURRENT_TIMESTAMP,0,1);

INSERT IGNORE INTO `doctor_consultation` (`id`,`patient_id`,`doctor_id`,`title`,`status`,`create_time`,`update_time`) VALUES
  (1,2,1,'血压记录咨询',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
  (1001,1001,1,'日常监测咨询',0,DATEADD('DAY',-5,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP),
  (1002,1002,1,'血压变化咨询',0,DATEADD('DAY',-3,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP),
  (1003,1003,2,'健康记录咨询',0,DATEADD('DAY',-4,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP);
INSERT IGNORE INTO `doctor_message` (`id`,`consultation_id`,`sender_id`,`receiver_id`,`content`,`message_type`,`is_read`,`create_time`) VALUES
  (1,1,2,1,'医生您好，我想了解如何记录血压。',0,1,CURRENT_TIMESTAMP),
  (2,1,1,2,'您好，建议每天在相近时间测量并记录。',0,0,CURRENT_TIMESTAMP);

INSERT IGNORE INTO `elder_risk_profile` (`id`,`user_id`,`age`,`sex_male`,`bmi`,`heart_disease`,`smoking_current`,`drinking_any`,`drinks_per_week`,`physical_activity`,`updated_at`) VALUES
  (1,2,72,1,25.40,0,0,0,0,1,CURRENT_TIMESTAMP);
INSERT IGNORE INTO `elder_vital_measurement` (`id`,`user_id`,`systolic_pressure`,`diastolic_pressure`,`heart_rate`,`spo2`,`temperature`,`measured_at`) VALUES
  (1,2,124,76,72,98,36.5,DATEADD('DAY',-6,CURRENT_TIMESTAMP)),
  (2,2,126,77,73,98,36.5,DATEADD('DAY',-5,CURRENT_TIMESTAMP)),
  (3,2,127,78,74,98,36.4,DATEADD('DAY',-4,CURRENT_TIMESTAMP)),
  (4,2,129,79,75,97,36.6,DATEADD('DAY',-3,CURRENT_TIMESTAMP)),
  (5,2,130,80,76,98,36.5,DATEADD('DAY',-2,CURRENT_TIMESTAMP)),
  (6,2,132,81,78,97,36.6,DATEADD('DAY',-1,CURRENT_TIMESTAMP)),
  (1001,1001,118,72,68,98,36.4,DATEADD('DAY',-9,CURRENT_TIMESTAMP)),
  (1002,1001,120,73,70,98,36.5,DATEADD('DAY',-6,CURRENT_TIMESTAMP)),
  (1003,1001,119,72,69,99,36.5,DATEADD('DAY',-3,CURRENT_TIMESTAMP)),
  (1004,1001,121,74,71,98,36.6,CURRENT_TIMESTAMP),
  (1005,1002,138,86,80,97,36.6,DATEADD('DAY',-9,CURRENT_TIMESTAMP)),
  (1006,1002,140,87,82,97,36.6,DATEADD('DAY',-6,CURRENT_TIMESTAMP)),
  (1007,1002,142,89,84,96,36.7,DATEADD('DAY',-3,CURRENT_TIMESTAMP)),
  (1008,1002,141,88,82,97,36.6,CURRENT_TIMESTAMP),
  (1009,1003,122,75,73,98,36.5,DATEADD('DAY',-9,CURRENT_TIMESTAMP)),
  (1010,1003,124,76,74,98,36.5,DATEADD('DAY',-6,CURRENT_TIMESTAMP)),
  (1011,1003,123,75,74,99,36.4,DATEADD('DAY',-3,CURRENT_TIMESTAMP)),
  (1012,1003,125,77,75,98,36.6,CURRENT_TIMESTAMP);
-- Complete only bundled sample respiration examples; oxygen/mental-state fields are not used by the five-indicator model.
UPDATE `elder_vital_measurement`
SET `respiratory_rate`=COALESCE(`respiratory_rate`,CASE WHEN `id`=1008 THEN 25 ELSE 16 END)
WHERE `id` IN (1,2,3,4,5,6,1001,1002,1003,1004,1005,1006,1007,1008,1009,1010,1011,1012);
INSERT IGNORE INTO `personalized_risk_assessment` (`id`,`user_id`,`group_risk_probability`,`personal_change_score`,`baseline_available`,`risk_level`,`reasons_json`,`model_name`,`model_version`,`assessment_time`) VALUES
  (1,2,0.420000,0.100000,1,'关注','["演示历史记录"]','XGBoost','demo-seed',CURRENT_TIMESTAMP);

-- Adult resting reference defaults.
-- Adult resting references, not emergency criteria. Preserve administrator customizations.
-- https://medlineplus.gov/ency/article/002341.htm
CREATE TABLE IF NOT EXISTS health_reference_activation (
 metric_key VARCHAR(40) NOT NULL PRIMARY KEY,
 first_measurement_id BIGINT NOT NULL
);
INSERT INTO health_reference_activation (metric_key,first_measurement_id)
SELECT 'temperature',(SELECT COALESCE(MAX(id),0)+1 FROM elder_vital_measurement)
WHERE NOT EXISTS (SELECT 1 FROM health_reference_activation WHERE metric_key='temperature')
AND (NOT EXISTS (SELECT 1 FROM health_model_config WHERE is_global=1 AND LOWER(symbol)='temp' AND value_range IS NOT NULL AND TRIM(value_range)<>'')
 OR EXISTS (SELECT 1 FROM health_model_config WHERE is_global=1 AND LOWER(symbol)='temp' AND detail LIKE '成人静息参考%'));
INSERT INTO health_reference_activation (metric_key,first_measurement_id)
SELECT 'respiratory_rate',(SELECT COALESCE(MAX(id),0)+1 FROM elder_vital_measurement)
WHERE NOT EXISTS (SELECT 1 FROM health_reference_activation WHERE metric_key='respiratory_rate')
AND (NOT EXISTS (SELECT 1 FROM health_model_config WHERE is_global=1 AND LOWER(symbol)='rr' AND value_range IS NOT NULL AND TRIM(value_range)<>'')
 OR EXISTS (SELECT 1 FROM health_model_config WHERE is_global=1 AND LOWER(symbol)='rr' AND detail LIKE '成人静息参考%'));
INSERT INTO health_model_config (name,detail,unit,symbol,is_global,value_range)
SELECT '体温','成人静息参考；需结合个体基线及测量部位，不作为急症判定','℃','temp',1,'36.5,37.3'
WHERE NOT EXISTS (SELECT 1 FROM health_model_config WHERE is_global=1 AND LOWER(symbol)='temp');
INSERT INTO health_model_config (name,detail,unit,symbol,is_global,value_range)
SELECT '呼吸频率','成人静息参考；需结合个体情况，不作为急症判定','次/分','rr',1,'12,18'
WHERE NOT EXISTS (SELECT 1 FROM health_model_config WHERE is_global=1 AND LOWER(symbol)='rr');
UPDATE health_model_config SET value_range='36.5,37.3' WHERE is_global=1 AND LOWER(symbol)='temp' AND (value_range IS NULL OR TRIM(value_range)='');
UPDATE health_model_config SET value_range='12,18' WHERE is_global=1 AND LOWER(symbol)='rr' AND (value_range IS NULL OR TRIM(value_range)='');
