-- ============================================================
-- 2026 软件开发综合实训 - 邮件系统
-- MySQL 8.0 建库脚本
-- ============================================================

CREATE DATABASE IF NOT EXISTS mail_system
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE mail_system;

-- -----------------------------------------------------------
-- 1. 用户表 (user)
-- -----------------------------------------------------------
DROP TABLE IF EXISTS `attachment`;
DROP TABLE IF EXISTS `mail_status`;
DROP TABLE IF EXISTS `mail`;
DROP TABLE IF EXISTS `user`;
DROP TABLE IF EXISTS `plugin_config`;

CREATE TABLE `user` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `email`      VARCHAR(128) NOT NULL COMMENT '邮箱地址',
  `password`   VARCHAR(256) NOT NULL COMMENT '密码（加密存储）',
  `nickname`   VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- -----------------------------------------------------------
-- 2. 邮件表 (mail)
-- -----------------------------------------------------------
CREATE TABLE `mail` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '邮件ID',
  `sender_id`    BIGINT       NOT NULL COMMENT '发件人用户ID',
  `sender_email` VARCHAR(128) NOT NULL COMMENT '发件人邮箱（冗余）',
  `receiver_ids` VARCHAR(512) NOT NULL COMMENT '收件人ID列表，逗号分隔',
  `cc_ids`       VARCHAR(512) DEFAULT NULL COMMENT '抄送人ID列表，逗号分隔',
  `subject`      VARCHAR(512) NOT NULL COMMENT '邮件主题',
  `body`         LONGTEXT     DEFAULT NULL COMMENT '邮件正文',
  `send_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
  `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '邮件状态: 1=正常, 0=已删除(发件人侧), 2=草稿',
  `priority`     INT          DEFAULT 0 COMMENT '智能优先级评分',
  `is_spam`      TINYINT      DEFAULT 0 COMMENT '是否垃圾邮件: 0=否, 1=是',
  `summary`      VARCHAR(1024) DEFAULT NULL COMMENT '智能摘要',
  `category`     VARCHAR(64)  DEFAULT NULL COMMENT '智能分类标签',
  PRIMARY KEY (`id`),
  KEY `idx_sender` (`sender_id`),
  KEY `idx_send_time` (`send_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮件表';

-- -----------------------------------------------------------
-- 3. 附件表 (attachment)
-- -----------------------------------------------------------
CREATE TABLE `attachment` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '附件ID',
  `mail_id`     BIGINT       DEFAULT NULL COMMENT '所属邮件ID（上传时可为空，发送邮件时绑定）',
  `file_name`   VARCHAR(256) NOT NULL COMMENT '原始文件名',
  `file_path`   VARCHAR(512) NOT NULL COMMENT '服务器存储路径',
  `file_size`   BIGINT       DEFAULT 0 COMMENT '文件大小（字节）',
  `content_type` VARCHAR(128) DEFAULT 'application/octet-stream' COMMENT 'MIME类型',
  `upload_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  PRIMARY KEY (`id`),
  KEY `idx_mail_id` (`mail_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='附件表';

-- -----------------------------------------------------------
-- 4. 邮件状态表 (mail_status)  -- 收件人/抄送人视角
-- -----------------------------------------------------------
CREATE TABLE `mail_status` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `mail_id`     BIGINT   NOT NULL COMMENT '邮件ID',
  `user_id`     BIGINT   NOT NULL COMMENT '用户ID（收件人或抄送人）',
  `is_read`     TINYINT  NOT NULL DEFAULT 0 COMMENT '是否已读: 0=未读, 1=已读',
  `is_deleted`  TINYINT  NOT NULL DEFAULT 0 COMMENT '是否已删除: 0=否, 1=是',
  `sync_status` TINYINT  NOT NULL DEFAULT 0 COMMENT '同步状态: 0=未同步, 1=已同步',
  `read_time`   DATETIME DEFAULT NULL COMMENT '阅读时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_mail` (`user_id`, `mail_id`),
  KEY `idx_mail_id` (`mail_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮件状态表';

-- -----------------------------------------------------------
-- 5. 插件配置表 (plugin_config)
-- -----------------------------------------------------------
CREATE TABLE `plugin_config` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '配置ID',
  `plugin_name` VARCHAR(64) NOT NULL COMMENT '插件名称',
  `enabled`     TINYINT     NOT NULL DEFAULT 1 COMMENT '是否启用: 0=禁用, 1=启用',
  `description` VARCHAR(256) DEFAULT NULL COMMENT '插件描述',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME    DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plugin_name` (`plugin_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='插件配置表';

-- -----------------------------------------------------------
-- 初始化插件配置数据
-- -----------------------------------------------------------
INSERT INTO `plugin_config` (`plugin_name`, `enabled`, `description`) VALUES
('spamFilter',       1, '垃圾邮件识别插件 - 基于关键词和规则引擎'),
('prioritySort',     1, '邮件优先级排序插件 - 按内容重要性评分'),
('linkDetection',    1, '恶意链接/伪造发件人检测插件'),
('summaryGenerator', 1, '智能摘要生成插件 - 异步任务'),
('categoryClassifier',1, '智能分类插件 - 异步任务');
