-- ============================================================
-- 邮件系统 v2 数据库迁移脚本
-- 新增: 状态时间追踪、同步支持、LLM配置表
-- ============================================================

USE mail_system;

-- 1. 为 mail_status 添加时间追踪列
ALTER TABLE `mail_status`
  ADD COLUMN IF NOT EXISTS `deleted_time` DATETIME DEFAULT NULL COMMENT '删除时间',
  ADD COLUMN IF NOT EXISTS `updated_time` DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '状态更新时间';

ALTER TABLE `mail_status`
  ADD INDEX IF NOT EXISTS `idx_updated_time` (`updated_time`);

-- 2. 创建 LLM 大模型配置表
CREATE TABLE IF NOT EXISTS `llm_config` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '配置ID',
  `api_endpoint` VARCHAR(512) NOT NULL DEFAULT 'https://api.openai.com/v1' COMMENT 'API端点',
  `api_key`      VARCHAR(512) DEFAULT NULL COMMENT 'API密钥',
  `model_name`   VARCHAR(128) NOT NULL DEFAULT 'gpt-3.5-turbo' COMMENT '模型名称',
  `enabled`      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否启用: 0=禁用, 1=启用',
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`  DATETIME     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM大模型配置表';

INSERT INTO `llm_config` (`api_endpoint`, `api_key`, `model_name`, `enabled`) VALUES
('https://api.openai.com/v1', '', 'gpt-3.5-turbo', 0)
ON DUPLICATE KEY UPDATE `id`=`id`;
