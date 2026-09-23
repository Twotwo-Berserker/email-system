-- ============================================================
-- 数据清理脚本
-- 清空用户和邮件数据和 LLM 配置
-- 使用：docker exec -i mail-mysql mysql -uroot -p"root123" < deploy/mysql/clear_data.sql
-- （cmd命令，root123是.env中的MYSQL_ROOT_PASSWORD）
--
-- 注意：本脚本会一并清空 user 表，管理员账号随之消失。
--       应用重启时 AdminSeeder 会依据 .env 中的 ADMIN_INIT_EMAIL /
--       ADMIN_INIT_PASSWORD 幂等重建管理员（must_change_password=1）。
--       因此清理后需<b>重启后端容器</b>才能重新登录管理端。
-- ============================================================

USE mail_system;

-- ------------------------------------------------------------
-- 用户数据（顺序：先子表后父表）
-- ------------------------------------------------------------

-- 按收件人的分析结果
DELETE FROM mail_intelligence_result;

-- 人工反馈
DELETE FROM user_feedback;

-- 外部邮箱账户（含加密后的 SMTP/IMAP 授权码）
DELETE FROM mail_account;

-- LLM 调用监控日志
DELETE FROM llm_call_log;

-- 清空附件关联
DELETE FROM attachment;

-- 清空邮件状态（收件人侧）
DELETE FROM mail_status;

-- 清空邮件
DELETE FROM mail;

-- 清空用户
DELETE FROM user;

-- ------------------------------------------------------------
-- 重置自增ID（从 1 开始）
-- ------------------------------------------------------------
ALTER TABLE mail_intelligence_result AUTO_INCREMENT = 1;
ALTER TABLE user_feedback  AUTO_INCREMENT = 1;
ALTER TABLE mail_account   AUTO_INCREMENT = 1;
ALTER TABLE llm_call_log   AUTO_INCREMENT = 1;
ALTER TABLE attachment     AUTO_INCREMENT = 1;
ALTER TABLE mail_status    AUTO_INCREMENT = 1;
ALTER TABLE mail           AUTO_INCREMENT = 1;
ALTER TABLE user           AUTO_INCREMENT = 1;

-- ------------------------------------------------------------
-- 清除 LLM 隐私配置（API 密钥等敏感信息）
-- 覆盖全部行：既有系统默认（user_id IS NULL），也有每用户自带的配置
-- ------------------------------------------------------------
UPDATE llm_config
   SET api_key       = NULL,
       api_key_last4 = NULL,
       enabled       = 0;

-- ------------------------------------------------------------
-- 保留不动的配置类数据：
--   plugin_config   —— 插件开关属于系统配置
--   prompt_template —— Prompt 模板属于系统配置
-- ------------------------------------------------------------
