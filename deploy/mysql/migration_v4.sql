-- ============================================================
-- 邮件系统 v4 数据库迁移脚本
--
-- 新增:
--   - mail_account.provider_type    账户来源（绑定外部邮箱 / 本域邮箱）
--   - mail_account.cloudflare_address 生成列 + 全局唯一约束（收信路由正确性）
--   - mail_account.idx_address      按收件地址查找（Cloudflare 收信入口）
--
-- 背景：
--   此前"收发外部邮件"只有一条路 —— 用户填自己的邮箱 + 授权码，
--   由本系统登录对方服务器（SMTP/IMAP）。v4 增加第二条路：用户领一个
--   本系统域名下的地址，收信由 Cloudflare Email Routing 在边缘接收后
--   推送进来，发信走项目自己配置的中继。两条路都不需要登录别人的邮箱，
--   因此后者完全不需要授权码。详见 Cloudflare.md。
--
-- ⚠️ 重要：本脚本不是幂等的（与 migration_v3.sql 同理）。
--    MySQL 8.0 不支持 `ADD COLUMN IF NOT EXISTS`（那是 MariaDB 专有语法），
--    重复执行会因 "Duplicate column name" 而中断。
--    若需重跑，请先用 deploy/mysql/clear_data.sql 清库，或
--    `docker-compose down -v` 清空数据卷后由 init.sql 重建。
--
-- 执行方式：
--   docker exec -i mail-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < deploy/mysql/migration_v4.sql
-- ============================================================

USE mail_system;

-- ------------------------------------------------------------
-- 1. provider_type —— 账户来源
--    DEFAULT 'IMAP_SMTP' 会把所有历史行一次性归位：它们是
--    "绑定外部邮箱、需要授权码"的那一类，这正是本字段存在之前的唯一形态。
-- ------------------------------------------------------------

ALTER TABLE `mail_account`
  ADD COLUMN `provider_type` VARCHAR(16) NOT NULL DEFAULT 'IMAP_SMTP'
      COMMENT '来源: IMAP_SMTP=绑定外部邮箱(需授权码) / CLOUDFLARE=本域邮箱(无需授权码)'
      AFTER `email_address`;

-- ------------------------------------------------------------
-- 2. cloudflare_address —— 收信地址的全局唯一约束
--
--    收信路由的正确性依赖它：一封发往 alice@mail.example.com 的信，
--    全库必须有且只有一个归属用户，否则投递会在多个账户之间随机选一个。
--
--    已有的 uk_user_email(user_id, email_address) 只保证"同一用户不重复绑定"，
--    挡不住两个用户抢同一个本域地址，因此必须再加一条。
--
--    MySQL 8 没有部分索引（WHERE 子句），标准做法是生成列：
--    只有 CLOUDFLARE 行才在这一列上取到值，其余行是 NULL，
--    而 MySQL 唯一索引不约束 NULL —— 等价于一个只作用于本域邮箱的部分唯一索引。
--
--    STORED 而非 VIRTUAL：唯一索引在 STORED 列上才能被可靠使用。
-- ------------------------------------------------------------

ALTER TABLE `mail_account`
  ADD COLUMN `cloudflare_address` VARCHAR(128) GENERATED ALWAYS AS (
      CASE WHEN `provider_type` = 'CLOUDFLARE' THEN `email_address` ELSE NULL END
  ) STORED COMMENT '生成列：仅 CLOUDFLARE 行的邮箱地址，用于全局唯一约束';

ALTER TABLE `mail_account`
  ADD UNIQUE KEY `uk_cloudflare_address` (`cloudflare_address`);

-- ------------------------------------------------------------
-- 3. idx_address —— Cloudflare 收信入口按收件地址查找
--    （生成的唯一索引只覆盖 CLOUDFLARE 行，且以生成列为索引键，
--      不便于 `WHERE email_address = ? AND provider_type = ?` 这类查询使用）
-- ------------------------------------------------------------

ALTER TABLE `mail_account`
  ADD INDEX `idx_address` (`email_address`);

-- ============================================================
-- 迁移完成
--
-- 后续由人工完成、无法由 SQL 表达的步骤：
--   1. 在 Cloudflare 上为本项目域名配置 Email Routing，并把
--      deploy/cloudflare/ 下的 Worker 部署上去（附带部署文档）
--   2. 在 .env 中配置 INBOUND_SHARED_SECRET（与 Worker 侧的密钥一致）
--      以及发信中继（OUTBOUND_TRANSPORT / RESEND_API_KEY 或 RELAY_SMTP_*）
--   3. 上述两项未配置时，收信 Webhook 与中继发信都会明确报"未启用"，
--      其余功能不受影响 —— 也就是说这个迁移只加能力，不改既有行为
-- ============================================================
