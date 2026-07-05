-- ============================================================
-- 数据清理脚本
-- 清空用户和邮件数据和 LLM 配置
-- 使用：docker exec -i mail-mysql mysql -uroot -p"root123" < deploy/mysql/clear_data.sql
-- （cmd命令，root123是.env中的MYSQL_ROOT_PASSWORD）
-- ============================================================

USE mail_system;

-- 清空附件关联
DELETE FROM attachment;

-- 清空邮件状态（收件人侧）
DELETE FROM mail_status;

-- 清空邮件
DELETE FROM mail;

-- 清空用户
DELETE FROM user;

-- 重置自增ID（从 1 开始）
ALTER TABLE attachment AUTO_INCREMENT = 1;
ALTER TABLE mail_status   AUTO_INCREMENT = 1;
ALTER TABLE mail          AUTO_INCREMENT = 1;
ALTER TABLE user          AUTO_INCREMENT = 1;

-- 清除 LLM 隐私配置（API 密钥等敏感信息）
UPDATE llm_config SET api_key = '', enabled = 0 WHERE id = 1;
