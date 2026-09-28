-- ============================================================
-- 修复种子数据的中文乱码（"你是" → "ä½ æ˜¯"）
--
-- 症状：
--   系统设置 →「分析 Prompt 版本」里的说明与正文预览是一片乱码，
--   插件管理里的插件描述同样是乱码。更严重的是 prompt_template.content
--   会以乱码的形式拼进请求发给大模型 —— 界面难看只是表象，
--   分析质量下降才是真问题。
--
-- 原因（不是代码问题，是导入方式问题）：
--   mysql 客户端在 LANG/LC_ALL 为空的容器里会把 character_set_client
--   退回 latin1。此时 init.sql / migration_v3.sql 里的中文按 UTF-8 编码的
--   字节被服务器当成 latin1 解读，再存进 utf8mb4 的列，于是每个字节都被
--   当成一个独立字符重新编码了一次 —— 这就是上面那种"每个汉字变两个怪字符"
--   的形态。
--
--   init.sql / migration_v2~v4.sql 现在都带上了 SET NAMES utf8mb4，
--   新导入不会再产生这个问题；已经导入过的库用本脚本修复。
--
-- 本脚本做什么：
--   把那段双编码按原路退回：先按 latin1 取回原始字节，再按 utf8mb4 重新解释。
--
-- 安全性：
--   两个条件同时成立才会被改，因此<b>可以重复执行</b>，也不会误伤：
--     1. 当前值里一个汉字都没有（已经正常的行，第一步就不满足）
--     2. 退回后能解出汉字（英文行、或压根不是这种乱码的行，第二步不满足）
--   用户从管理后台新建的模板走的是 JDBC（连接串里带 characterEncoding=utf-8），
--   本来就是好的，不会被这两条命中。
--
-- 执行：
--   docker exec -i mail-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
--     --default-character-set=utf8mb4 < deploy/mysql/repair_mojibake.sql
-- ============================================================

SET NAMES utf8mb4;

USE mail_system;

-- ------------------------------------------------------------
-- 1. plugin_config.description —— 插件管理页的插件说明
-- ------------------------------------------------------------
UPDATE `plugin_config`
SET `description` = CONVERT(BINARY(CONVERT(`description` USING latin1)) USING utf8mb4)
WHERE `description` NOT REGEXP '[一-龥]'
  AND CONVERT(BINARY(CONVERT(`description` USING latin1)) USING utf8mb4) REGEXP '[一-龥]';

-- ------------------------------------------------------------
-- 2. prompt_template —— 分析 Prompt 的正文与说明
--    正文乱码会直接影响分析质量，比界面难看严重得多
-- ------------------------------------------------------------
UPDATE `prompt_template`
SET `content`     = CONVERT(BINARY(CONVERT(`content` USING latin1)) USING utf8mb4),
    `description` = CONVERT(BINARY(CONVERT(`description` USING latin1)) USING utf8mb4)
WHERE `content` NOT REGEXP '[一-龥]'
  AND CONVERT(BINARY(CONVERT(`content` USING latin1)) USING utf8mb4) REGEXP '[一-龥]';

-- ------------------------------------------------------------
-- 校验：两个查询都应返回 0 行
--
-- 必须用 LIKE BINARY。库和表的排序规则是 utf8mb4_unicode_ci，
-- 在这种"重音不敏感"的规则下 ä 与 a 相等 —— 写成 `LIKE '%ä%'` 会把
-- 正文里的 "spam" 也算成乱码，查出个永远清不掉的 1。
-- LIKE BINARY 按字节比较，才是这里想要的语义。
-- ------------------------------------------------------------
SELECT 'plugin_config 仍有乱码' AS check_name, COUNT(*) AS `剩余行数`
FROM `plugin_config`
WHERE `description` LIKE BINARY '%ä%' OR `description` LIKE BINARY '%æ%' OR `description` LIKE BINARY '%å%'
UNION ALL
SELECT 'prompt_template 仍有乱码', COUNT(*)
FROM `prompt_template`
WHERE `content` LIKE BINARY '%ä%' OR `content` LIKE BINARY '%æ%' OR `content` LIKE BINARY '%å%';
