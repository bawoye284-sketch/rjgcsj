-- Non-destructive initialization for a new MySQL 8 database.
-- Existing tables and records are preserved; this does not migrate existing columns.
CREATE DATABASE IF NOT EXISTS `personal_health` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `personal_health`;

CREATE TABLE IF NOT EXISTS `ai_model_log`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `model_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '模型名称',
  `api_endpoint` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'API端点',
  `request_time` datetime NULL DEFAULT NULL COMMENT '请求时间',
  `response_time` int NULL DEFAULT NULL COMMENT '响应时间(ms)',
  `status` tinyint(1) NULL DEFAULT NULL COMMENT '状态(0:失败,1:成功)',
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '错误信息',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI模型调用日志' ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `doctor`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '医生ID',
  `user_id` int NOT NULL COMMENT '关联用户ID',
  `name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '医生姓名',
  `account` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '登录账号',
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '登录密码',
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '医生头像',
  `specialty` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '专业领域',
  `title` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '职称',
  `hospital` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '所属医院',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '个人简介',
  `status` tinyint(1) NULL DEFAULT 1 COMMENT '状态(0:停诊,1:接诊)',
  `consultation_count` int NULL DEFAULT 0 COMMENT '咨询次数',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1971712002 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '医生信息表' ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `doctor_consultation`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话ID',
  `patient_id` int NOT NULL COMMENT '患者用户ID',
  `doctor_id` int NOT NULL COMMENT '医生ID',
  `title` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '咨询主题',
  `status` tinyint(1) NULL DEFAULT 0 COMMENT '状态(0:进行中,1:已结束)',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL COMMENT '最后更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_patient`(`patient_id` ASC) USING BTREE,
  INDEX `idx_doctor`(`doctor_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 549838853 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '医患会话表' ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `doctor_message`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '消息ID',
  `consultation_id` int NOT NULL COMMENT '关联会话ID',
  `sender_id` int NOT NULL COMMENT '发送者ID',
  `receiver_id` int NOT NULL COMMENT '接收者ID',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '消息内容',
  `message_type` tinyint(1) NULL DEFAULT 0 COMMENT '消息类型(0:文本,1:图片,2:健康数据)',
  `health_data_id` int NULL DEFAULT NULL COMMENT '关联健康数据ID',
  `is_read` tinyint(1) NULL DEFAULT 0 COMMENT '是否已读(0:未读,1:已读)',
  `create_time` datetime NULL DEFAULT NULL COMMENT '发送时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_consultation`(`consultation_id` ASC) USING BTREE,
  INDEX `idx_sender`(`sender_id` ASC) USING BTREE,
  INDEX `idx_receiver`(`receiver_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1925570584 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '医患聊天消息表' ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `evaluations`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `parent_id` int NULL DEFAULT NULL COMMENT '父级评论ID',
  `commenter_id` int NULL DEFAULT NULL COMMENT '评论者ID',
  `replier_id` int NULL DEFAULT NULL COMMENT '回复者ID',
  `content_type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '内容类型',
  `content_id` int NULL DEFAULT NULL COMMENT '内容ID',
  `content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '评论内容',
  `upvote_list` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '点赞列表，以\",\"分割',
  `create_time` datetime NULL DEFAULT NULL COMMENT '评论时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 203 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin ROW_FORMAT = DYNAMIC;

CREATE TABLE IF NOT EXISTS `food`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '食物表主键ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '食物名',
  `tag_id` int NULL DEFAULT NULL COMMENT '标签分类ID',
  `detail` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '食物做法',
  `create_time` datetime NULL DEFAULT NULL COMMENT '发布时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin ROW_FORMAT = DYNAMIC;

CREATE TABLE IF NOT EXISTS `health_advice`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '用户ID',
  `advice_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '建议类型',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '建议内容',
  `source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '来源(AI/专家)',
  `priority` tinyint NULL DEFAULT NULL COMMENT '优先级(1-5)',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `is_read` tinyint NULL DEFAULT 0 COMMENT '是否已读(0:否,1:是)',
  `is_implemented` tinyint NULL DEFAULT 0 COMMENT '是否已执行(0:否,1:是)',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '健康建议表' ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `health_model_config`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '健康模型自增主键',
  `user_id` int NULL DEFAULT NULL COMMENT '用户ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '配置名',
  `detail` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '配置简介',
  `cover` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '配置图标',
  `unit` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '配置单位',
  `symbol` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '配置符号',
  `is_global` tinyint(1) NULL DEFAULT NULL COMMENT '是否是全局模型',
  `value_range` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '正常值范围',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 13 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '健康模型表' ROW_FORMAT = DYNAMIC;

CREATE TABLE IF NOT EXISTS `health_prediction`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '用户ID',
  `metric_type` int NULL DEFAULT NULL COMMENT '指标类型',
  `prediction_time` datetime NULL DEFAULT NULL COMMENT '预测时间点',
  `predicted_value` decimal(10, 2) NULL DEFAULT NULL COMMENT '预测值',
  `confidence` decimal(5, 2) NULL DEFAULT NULL COMMENT '置信度(0-1)',
  `model_version` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '模型版本',
  `creation_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_metric`(`user_id` ASC, `metric_type` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '预测结果表' ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `health_risk_assessment`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '用户ID',
  `assessment_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '评估类型',
  `risk_level` tinyint NULL DEFAULT NULL COMMENT '风险等级(1-5)',
  `risk_factors` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '风险因素JSON',
  `assessment_result` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '评估结果JSON',
  `assessment_time` datetime NULL DEFAULT NULL COMMENT '评估时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '健康风险评估表' ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `message`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '消息表的自增主键ID',
  `content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '消息内容（消息体）',
  `message_type` int NULL DEFAULT NULL COMMENT '消息类型（指标数据、互动、系统消息...）',
  `receiver_id` int NULL DEFAULT NULL COMMENT '接收者ID',
  `sender_id` int NULL DEFAULT NULL COMMENT '发送者ID',
  `is_read` tinyint(1) NULL DEFAULT NULL COMMENT '消息是否已经阅读',
  `content_id` int NULL DEFAULT NULL COMMENT '关联的内容ID',
  `create_time` datetime NULL DEFAULT NULL COMMENT '消息的发送时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 54 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin ROW_FORMAT = DYNAMIC;

CREATE TABLE IF NOT EXISTS `news`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '健康资讯ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '标题',
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '内容',
  `tag_id` int NULL DEFAULT NULL COMMENT '标签ID',
  `cover` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '图片封面',
  `reader_ids` text CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '阅读者ID列表，用“,”分割',
  `is_top` tinyint(1) NULL DEFAULT NULL COMMENT '是否推荐',
  `create_time` datetime NULL DEFAULT NULL COMMENT '发布时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 13 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '健康资讯表' ROW_FORMAT = DYNAMIC;

CREATE TABLE IF NOT EXISTS `news_save`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '资讯的收藏ID',
  `user_id` int NULL DEFAULT NULL COMMENT '用户ID',
  `news_id` int NULL DEFAULT NULL COMMENT '资讯ID',
  `create_time` datetime NULL DEFAULT NULL COMMENT '收藏的时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 49 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '健康资讯收藏表' ROW_FORMAT = DYNAMIC;

CREATE TABLE IF NOT EXISTS `tags`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '标签ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '标签分类名',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 9 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '标签分类表' ROW_FORMAT = DYNAMIC;

CREATE TABLE IF NOT EXISTS `user`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户编号',
  `user_account` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '用户账号',
  `user_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '用户昵称',
  `user_pwd` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '用户密码',
  `user_avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '用户头像',
  `user_email` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '用户邮箱',
  `user_role` int NULL DEFAULT NULL COMMENT '用户角色',
  `is_login` tinyint(1) NULL DEFAULT NULL COMMENT '可登录状态(0：可用，1：不可用)',
  `is_word` tinyint(1) NULL DEFAULT NULL COMMENT '禁言状态(0：可用，1：不可用)',
  `create_time` datetime NULL DEFAULT NULL COMMENT '用户注册时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 23 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE IF NOT EXISTS `user_health`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户健康记录主键',
  `user_id` int NULL DEFAULT NULL COMMENT '用户ID',
  `health_model_config_id` int NULL DEFAULT NULL COMMENT '健康模型ID',
  `value` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '输入的值',
  `create_time` datetime NULL DEFAULT NULL COMMENT '记录时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 86 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '用户健康记录表' ROW_FORMAT = DYNAMIC;

CREATE TABLE IF NOT EXISTS `user_profile`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '用户ID',
  `health_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '健康状态分类',
  `risk_level` tinyint NULL DEFAULT NULL COMMENT '健康风险等级(1-5)',
  `active_level` tinyint NULL DEFAULT NULL COMMENT '活跃度(1-5)',
  `preference_tags` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '偏好标签',
  `last_updated` datetime NULL DEFAULT NULL COMMENT '最后更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户画像表' ROW_FORMAT = Dynamic;

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
