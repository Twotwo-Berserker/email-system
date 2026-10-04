# 部署方案

本文分两部分：**第一部分**说明当前版本为什么只适合本地 / 单机运行，
**第二部分**给出把它推到公网可用的分阶段路线与逐项改造清单。

> 收信链路的配置细节见 [Cloudflare.md](Cloudflare.md) 与 [deploy/cloudflare/README.md](../deploy/cloudflare/README.md)，
> 发信中继见 [Resend.md](Resend.md)。本文不重复它们，只讲"上线"这件事。

---

# 第一部分：当前状态

当前版本的默认配置是为**本机联调**准备的，直接暴露到公网会在几个具体的地方出问题，
而不是"理论上不够安全"。

| # | 现状 | 上线后会发生什么 | 位置 |
|---|---|---|---|
| 1 | 附件预签名 URL 由 `MINIO_ENDPOINT` 生成，默认 `http://localhost:9000` | **服务端在签名里写死了 `localhost`**，浏览器拿到链接后在**访问者自己的机器**上找 9000 端口 → 附件预览 / 下载全部失效 | [MinioStorageService.java:167](../backend/src/main/java/com/mailsystem/service/MinioStorageService.java#L167)、[application.yml:67](../backend/src/main/resources/application.yml#L67) |
| 2 | Nginx 仅监听 80，`server_name localhost`，无 TLS | 账号密码、JWT、邮件正文全程明文传输 | [deploy/nginx/default.conf:17-18](../deploy/nginx/default.conf#L17-L18) |
| 3 | MySQL / Redis / MinIO / Backend 端口全部 `ports` 映射到宿主机 | 3306 / 6379 / 9000 / 9001 / 8080 一并暴露公网，等于绕过 Nginx 与全部鉴权 | [docker-compose.yml](../docker-compose.yml) |
| 4 | `.env.example` 含**真实**密钥且已进 git 历史；`JWT_SECRET`、`AES_SECRET_KEY`、`INBOUND_SHARED_SECRET` 都是可直接使用的值 | 任何拿到仓库的人都能伪造 JWT、解密密文凭据、伪造入站邮件 | `.env.example` |
| 5 | 默认口令：`ADMIN_INIT_PASSWORD=123456`、MySQL `root123`、Redis `redis_secure_password_2026`、MinIO `minioadmin123` | 弱口令直接对应一个可登录的管理员账号 | `.env.example` |
| 6 | Cloudflare Worker 要求后端是**公网 HTTPS 地址** | 本机 `localhost` 收不到外部来信，需临时隧道才能联调 | [deploy/cloudflare/README.md](../deploy/cloudflare/README.md) |
| 7 | 协议代理不做 TLS | 邮件客户端的账号密码明文经过网络 | [proxy/README.md](../proxy/README.md) |
| 8 | 单机、命名卷、无备份、无监控 | 宿主机故障即全部数据丢失，且出事时没有现场 | — |

> **一句话**：功能链路是通的，缺的是"公网化"这一层 —— 域名 / 证书 / 端口收敛 / 密钥轮换。

---

# 第二部分：未来部署方案

## 目标架构

```
                    ┌──────────────────────────────┐
  用户浏览器 ──443──▶│  云主机（单机起步）           │
  邮件客户端 ──────▶│                              │
                    │  Nginx :80/:443  ← TLS 终结   │
                    │    ├── /        前端静态资源   │
                    │    ├── /api/    Backend:8080 │
                    │    └── /files/  MinIO:9000   │
                    │  Backend ── MySQL / Redis / MinIO（仅内网）
                    └──────────────────────────────┘
  外部来信 ──MX──▶ Cloudflare Email Routing ──HTTPS──▶ /inbound/cloudflare
  本域发信 ◀── Resend HTTP API（走 443）
```

关键点：**只有 Nginx 对公网开放 80/443，其余组件一律退回容器内网。**

## 阶段路线

| 阶段 | 目标 | 主要工作 |
|---|---|---|
| **0. 本地联调**（当前） | 本机跑通全部功能 | `docker compose up -d` |
| **1. 最小公网可用** | 一个域名下能正常收发 | 域名 + HTTPS + 端口收敛 + 密钥轮换 + MinIO 外链 |
| **2. 可运维** | 出事能发现、数据能恢复 | 备份、日志、监控告警、限流 |
| **3. 可扩展** | 支撑多用户与增长 | 数据组件外置托管、多实例、CDN |

---

## 阶段 1：最小公网可用

### 1.1 域名与 HTTPS

- 准备一个域名，A 记录指向云主机。
- Nginx 增加 443 server 块，证书用 Let's Encrypt（certbot 或 Caddy 自动签发）。
  把 [deploy/nginx/default.conf](../deploy/nginx/default.conf) 中的 `server_name localhost`
  换成实际域名（配置里已标注为「生产环境替换项」）。
- 80 端口保留，仅用于跳转到 443。
- 若用子域名专门做邮箱（推荐，如 `mail.example.com`），注意**域名本身的 MX**
  与**邮箱子域的 MX** 是两条独立记录，不要互相覆盖。

### 1.2 收敛暴露端口

把 [docker-compose.yml](../docker-compose.yml) 中除 `nginx` 外的所有 `ports` 改为 `expose`，
或绑定回环地址：

```yaml
# 需要从宿主机直连调试时，绑定到本地而非 0.0.0.0
ports:
  - "127.0.0.1:8080:8080"
```

- `mysql` / `redis` / `minio` / `backend`：**删掉 `ports` 或绑定 127.0.0.1**。
  它们在 `mail-network` 内用服务名互访，不需要对宿主机暴露。
- `proxy` 的 143 / 2525 若要给外部客户端用，保持开放，但见 1.6。
- MinIO 的 9001 控制台：不要对公网开放；如需远程管理，走 SSH 隧道。

### 1.3 密钥轮换（必做）

`.env.example` 里的密钥历史上已进入 git，**必须全部重新生成**，且不得再提交真实值：

```bash
openssl rand -base64 32   # AES_SECRET_KEY
openssl rand -hex 32      # JWT_SECRET
openssl rand -hex 32      # INBOUND_SHARED_SECRET（需与 Worker 侧同步更新）
```

同时替换：`MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`REDIS_PASSWORD`、
`MINIO_ROOT_PASSWORD`、`ADMIN_INIT_PASSWORD`。

> `AES_SECRET_KEY` 一旦更换，此前加密落库的邮箱授权码与 LLM API Key 将无法解密
> （应用会明确报错而非静默失败），需要在界面上重新录入。

`.env.example` 应只保留占位符；真实 `.env` 保持在 `.gitignore` 内，
在生产机上通过密钥管理（云厂商 Secret Manager / 部署时注入）下发。

### 1.4 附件外链

后端用 `MINIO_ENDPOINT` 生成预签名 URL，这个值会原样出现在返回给浏览器的链接里。
上服务器后必须把它改成**外部可达的地址**，并且该地址要能路由到 MinIO：

```bash
# .env
MINIO_ENDPOINT=https://mail.example.com/files
```

对应 Nginx 增加一段反代（MinIO 的签名包含 Host，因此必须原样透传）：

```nginx
location /files/ {
    proxy_pass http://minio:9000/;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    client_max_body_size 100m;
}
```

改完后务必验证：**在另一台机器上**打开一封带附件的邮件，点预览 / 下载能成功 ——
在部署机本机测是测不出这个问题的。

### 1.5 Cloudflare 收信

后端需要是公网 HTTPS 可达的，Worker 侧把 `BACKEND_INBOUND_URL` 指向
`https://mail.example.com/api/inbound/cloudflare`，并确保：

- `.env` 中 `INBOUND_ENABLED=true`、`INBOUND_DOMAINS` 与实际配了 MX 的域名一致；
- `INBOUND_SHARED_SECRET` 与 `wrangler secret put` 写入的值完全相同；
- Nginx 的 `client_max_body_size` 不小于后端入站报文上限，否则大附件来信会被 413 拦掉。

> 本地想先联调，用 `cloudflared tunnel` 或 `ngrok` 把本地 8080 临时暴露成 HTTPS 即可。

### 1.6 发信中继

按 [Resend.md](Resend.md) 在 Resend 验证 `INBOUND_DOMAINS` 里的每一个域名。
选 Resend 而非 SMTP 的原因很实际：**多数云主机默认封禁出站 25 / 465 / 587**，
而 443 永远开着。

### 1.7 协议代理

代理本身不做 TLS，生产环境应在它前面放一个 TLS 终结
（Nginx `stream` 模块 / stunnel），而不是让代理自己管证书 ——
这也是它刻意保持简单的原因。若只服务内网用户，可暂不暴露。

---

## 阶段 2：可运维

| 项 | 建议 |
|---|---|
| 数据备份 | `mysqldump` 每日全量 + binlog；MinIO 桶用 `mc mirror` 同步到异地。**定期做恢复演练**，没恢复过的备份等于没有备份 |
| 日志 | 容器日志限制大小（`logging.options.max-size`）并集中收集；后端已用 `llm_call_log` 记录 LLM 调用成本与失败率，纳入监控 |
| 监控告警 | 至少覆盖：容器存活、MySQL / Redis / MinIO 可用性、磁盘水位、后端 5xx 比例、IMAP 同步失败率、外部投递失败率 |
| 限流 | Nginx 层对 `/api/user/login`、`/api/user/register` 加 `limit_req`；对 `/api/inbound/` 按来源加白名单 |
| JVM | `backend/Dockerfile` 中的 `-Xms256m -Xmx512m` 按实际内存调整，并设置容器内存上限，避免 OOM 拖垮宿主机 |
| 升级 | 前端改动后需 `docker compose up -d --build frontend`；数据库结构变更按 `migration_vN.sql` 顺序执行，执行前先备份 |

---

## 阶段 3：可扩展

当单机成为瓶颈时，按代价从低到高：

1. **数据组件外置** —— MySQL / Redis / MinIO 换成云托管服务，应用无状态化。
   注意 MinIO 换成云对象存储后，`MINIO_ENDPOINT` 与预签名逻辑需相应调整。
2. **多实例** —— Backend 无状态，可水平扩容到 Nginx 上游。
   但需处理两个有状态点：
   - **定时任务**（IMAP 轮询）在每个实例都会跑，需要分布式锁或独立调度实例；
   - **WebSocket** 推送需要跨实例广播（Redis Pub/Sub 或 STOMP broker relay）。
3. **前端与静态资源** —— 当前前端产物写入命名卷、由 Nginx 挂载，这是单机模式。
   多机时改为构建镜像或推 CDN。
4. **CDN** —— 静态资源与附件下载走 CDN，但注意预签名 URL 的有效期与回源鉴权。

---

## 上线检查清单

**安全**
- [ ] 全部默认口令与密钥已重新生成，`.env.example` 无真实值
- [ ] `.env` 不在版本控制内，生产密钥经密钥管理下发
- [ ] MySQL / Redis / MinIO / Backend 未对公网暴露
- [ ] MinIO 控制台不对公网开放
- [ ] 管理员初始密码已修改（系统强制首次改密）
- [ ] 全站 HTTPS，80 仅做跳转
- [ ] 登录 / 注册接口已限流，`/api/inbound/` 已限制来源

**功能**
- [ ] `server_name` 已替换为实际域名
- [ ] `MINIO_ENDPOINT` 指向外部可达地址，**在另一台机器上**验证附件预览 / 下载
- [ ] Cloudflare Worker 能成功投递，退信逻辑符合预期（见 Cloudflare.md）
- [ ] Resend（或 SMTP 中继）已通过域名验证，本域地址能发出并能收到回信
- [ ] 协议代理的 TLS 终结已就位，客户端加密设置正确

**运维**
- [ ] 数据库与对象存储的备份任务已生效，且**做过一次恢复演练**
- [ ] 监控告警覆盖容器、磁盘、5xx、同步失败率
- [ ] 容器日志已限制大小，不会写满磁盘
- [ ] JVM 内存与容器内存上限匹配
