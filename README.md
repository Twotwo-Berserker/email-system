# 📧 邮件系统 — 2026 软件开发综合实训Demo

> 全栈 Web 邮件系统，Vue3 + Element Plus 前端，SpringBoot 后端，MySQL 数据库，Redis 缓存，MinIO 对象存储
>
> **纯 Docker 部署模式** — 无需本地 JDK / Node.js / Maven / MySQL / Redis / MinIO

---

## 🚀 一键启动指南

### 前置条件

唯一依赖：**Docker** + **Docker Compose**

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (Windows / Mac)
- 或 [Docker Engine](https://docs.docker.com/engine/install/) + [Docker Compose](https://docs.docker.com/compose/install/) (Linux)

### 启动步骤

```bash
# 1. 复制环境变量模板并修改（特别是密码与 AES_SECRET_KEY）
cp .env.example .env

# 2. 构建并启动所有服务
docker-compose up -d
# 修改fronted后需 docker compose up -d --build frontend

# 3. 查看服务运行状态
docker-compose ps

# 4. 查看各服务日志
docker-compose logs -f
```

> **`AES_SECRET_KEY` 必须填写**，否则后端**拒绝启动**并打印生成命令。它用于加密落库的邮箱授权码与 LLM API Key。
> 复制模板后请务必替换成自己生成的密钥：
>
> ```bash
> openssl rand -base64 32
> ```
>
> 该值一旦变更，已加密的凭据将无法解密（应用会明确报错而不是静默失败），需要重新录入。

> **管理员账号**：首次启动时按 `.env` 中的 `ADMIN_INIT_EMAIL` / `ADMIN_INIT_PASSWORD` 幂等创建，角色为 `ADMIN`，并强制首次登录修改密码。

## 关闭环境

```bash
# 停止基础设施（数据卷保留）
docker-compose down

# 停止并删除数据卷（完全重置）
docker-compose down -v
```

### 访问地址

| 服务 | 地址 | 说明 |
|------|------|------|
| 前端页面 | `http://localhost` | Nginx → 前端静态资源 |
| 后端 API | `http://localhost/api/` | Nginx → Backend (8080) |
| MinIO 控制台 | `http://localhost:9001` | 对象存储管理界面 |
| 邮件客户端（IMAP） | `localhost:143` | 协议代理。用户名/密码 = 本系统账号，见 [proxy/README.md](proxy/README.md) |
| 邮件客户端（SMTP） | `localhost:2525` | 同上。客户端里加密方式需选「无」 |

> 后两项是给 Thunderbird / Outlook / 手机邮件 App 用的，Web 端用户不需要关心。
> 不需要这套协议代理时，把 `docker-compose.yml` 里的 `proxy` 服务整段删掉即可，
> 其余服务不受影响。

---

## 🐳 架构与部署

### 整体架构

```
  外部发件人 ──▶ 本项目域名的 MX ──▶ Cloudflare Email Routing ──▶ Worker
                    （可选，见 Cloudflare.md）        │ HMAC 签名
                                                      ▼
                         ┌──────────────────┐    ┌──────────────────┐
  邮件客户端 ──SMTP/IMAP─▶│   Proxy :143     │    │                  │
  （可选）                │   :2525          │    │                  │
                         └────────┬─────────┘    │                  │
                                  │ HTTP + JWT   │                  │
                         ┌────────▼──────────────▼──────┐           │
                         │         Nginx :80            │◀──────────┘
                         │      deploy/nginx/           │
                         └────────┬─────────────────────┘
                                  │
           ┌──────────────────────┼──────────────────────┐
           │                      │                      │
    ┌──────▼──────┐       ┌──────▼──────┐       ┌──────▼──────┐
    │  Frontend   │       │   Backend   │       │MinIO Console│
    │  (构建产物) │       │   :8080     │       │   :9001     │
    │ 命名卷共享  │       └──────┬──────┘       └─────────────┘
    └─────────────┘              │
                    ┌────────────┼────────────┐
                    │            │            │
              ┌─────▼────┐ ┌────▼───┐ ┌──────▼──────┐
              │  MySQL   │ │ Redis  │ │    MinIO    │
              │  :3306   │ │ :6379  │ │    :9000    │
              └──────────┘ └────────┘ └─────────────┘
```

图中虚线以上的部分是**可选的对外收发能力**：不做任何配置时系统就是一个完整的
站内邮件系统；配了 Cloudflare 才能收外部来信，配了发信中继才能往外部发信。
两者的取舍见 [Cloudflare.md](Cloudflare.md)。

### 服务组件说明

| 服务 | 构建来源 | 端口 | 用途 |
|------|----------|------|------|
| Nginx | `deploy/nginx/Dockerfile` | 80 | 反向代理、静态资源服务、API 转发 |
| Frontend | `frontend/Dockerfile` | — | Vue3 前端构建（产物写入共享卷） |
| Backend | `backend/Dockerfile` | 8080 | SpringBoot 邮件系统核心服务 |
| MySQL | `mysql:8.0`（官方镜像） | 3306 | 关系型数据库 |
| Redis | `deploy/redis/Dockerfile` | 6379 | 缓存、验证码、JWT Token 黑名单 |
| MinIO | `minio/minio`（官方镜像） | 9000/9001 | 对象存储 API + Web 控制台 |
| MinIO-Init | `minio/mc`（一次性服务） | — | 创建存储桶、设置策略 |
| Proxy | `proxy/Dockerfile` | 143 / 2525 | SMTP/IMAP 协议代理，给传统邮件客户端用 |

### 启动流程

1. **MySQL / Redis / MinIO** 先启动并完成健康检查
2. **Backend** 等待 MySQL、Redis、MinIO 就绪后启动
3. **Frontend** 在 Docker 内执行 `npm ci` → `npm run build`，产物写入 `frontend-dist` 命名卷
4. **Nginx** 等待 Frontend 构建完成 + Backend 就绪后启动，挂载 `frontend-dist` 卷提供静态资源
5. **MinIO-Init** 在 MinIO 就绪后执行一次，创建存储桶
6. **Proxy** 等待 Backend 健康后启动，监听 143 / 2525

---

## 📁 项目结构

```
mail-system/
├── backend/                         # SpringBoot 后端
│   ├── Dockerfile                   # 后端 Docker 镜像（Maven 多阶段构建）
│   ├── pom.xml
│   └── src/main/java/com/mailsystem/
│       ├── MailSystemApplication.java
│       ├── config/                  # 配置类
│       ├── controller/              # REST 控制器
│       ├── service/                 # 业务逻辑层
│       ├── mapper/                  # MyBatis 数据访问层
│       ├── entity/                  # 数据库实体
│       ├── dto/                     # 数据传输对象
│       ├── event/                   # 事务提交后触发的领域事件（外部投递、分析）
│       ├── plugin/                  # 智能插件系统（LLM 失败时作为规则兜底）
│       ├── websocket/               # STOMP 实时推送
│       └── util/                    # 工具类（含 AES 凭据加解密、SMTP/IMAP 连接工厂）
├── frontend/                        # Vue3 前端
│   ├── Dockerfile                   # 前端 Docker 镜像（Node 构建 → 产物导出）
│   ├── package.json
│   ├── vite.config.js
│   └── src/
│       ├── api/                     # API 封装
│       ├── composables/             # 组合式函数（缓存、WebSocket、邮件操作）
│       ├── utils/                   # 工具函数
│       ├── views/                   # 页面视图
│       │   └── admin/               # 管理端页面（用户管理、反馈统计、邮箱监控、系统配置）
│       ├── router/                  # 路由配置（含登录/管理员/强制改密三级守卫）
│       ├── stores/                  # Pinia 状态管理
│       └── layouts/                 # 布局组件
├── deploy/                          # 部署配置
│   ├── nginx/
│   │   ├── Dockerfile               # Nginx 反向代理镜像
│   │   └── default.conf             # Nginx 配置（静态资源 + API 代理）
│   ├── redis/
│   │   ├── Dockerfile               # Redis 生产镜像
│   │   └── redis.conf               # Redis 生产配置
│   ├── minio/
│   │   └── init.sh                  # MinIO 存储桶初始化脚本
│   └── mysql/
│       ├── init.sql                 # 数据库建库脚本（新库唯一权威 schema）
│       ├── migration_v2.sql         # v2 升级迁移（新增时间追踪、LLM配置表）
│       ├── migration_v3.sql         # v3 升级迁移（管理员/外部邮箱/分析结果/反馈）
│       ├── migration_v4.sql         # v4 升级迁移（本域地址：provider_type 与唯一索引）
│       └── clear_data.sql           # 清空用户/邮件数据
├── deploy/cloudflare/               # Cloudflare Email Worker（本域地址收信）
│   ├── src/index.js                 # Worker 本体：读原始报文 → HMAC 签名 → 转发后端
│   ├── wrangler.toml                # 部署配置（密钥不写在这里）
│   └── README.md                    # 部署与排障
├── proxy/                           # SMTP/IMAP 协议代理（Python，零第三方依赖）
│   ├── server.py                    # IMAP 命令循环 + SMTP 状态机
│   ├── backend.py                   # 后端 REST 封装
│   ├── mime.py                      # RFC822 报文解析（供 ENVELOPE / BODYSTRUCTURE 用）
│   └── README.md                    # 客户端配置与已知限制
├── doc/API.md                       # 接口文档
├── Cloudflare.md                    # 「无需授权码收发邮件」的原理与配置
├── docker-compose.yml               # Docker Compose 编排文件
├── .env.example                     # 环境变量模板
└── README.md                        # 本文件
```

---

## 🔧 关键配置说明

### Docker 构建流程

前端、后端、Nginx 均通过 Dockerfile 多阶段构建，无需本地安装任何运行时：

- **前端构建**（`frontend/Dockerfile`）：`node:18-alpine` → `npm ci` → `npm run build` → 产物导出
- **后端构建**（`backend/Dockerfile`）：`maven:3.9-amazoncorretto-21` → `mvn package` → JAR → `amazoncorretto:21-alpine`
- **Nginx 构建**（`deploy/nginx/Dockerfile`）：`nginx:1.25-alpine` + 自定义配置

### Nginx 配置

配置文件位于 `deploy/nginx/default.conf`，负责：

- 前端静态资源服务（SPA 路由 fallback、静态资源缓存 30 天）
- 后端 API 反向代理（`/api/` → `backend:8080`，自动去除 `/api` 前缀）
- MinIO 控制台代理（`/minio-console/` → `minio:9001`）
- 大文件上传支持（`client_max_body_size: 100m`）

### 数据库连接

所有连接信息通过 `.env` 环境变量注入，Docker 内部通过服务名互相发现：

```yaml
# application.yml 中的 Docker profile 自动切换
spring:
  datasource:
    url: jdbc:mysql://mysql:3306/${MYSQL_DATABASE}?...
  redis:
    host: redis
minio:
  endpoint: http://minio:9000
```

### Redis 配置

- **密码认证**：通过 `.env` 中的 `REDIS_PASSWORD` 设置，启动时由 `--requirepass` 注入
- **持久化**：RDB 快照 + AOF 日志双写，数据存储在 `redis-data` 命名卷
- **内存策略**：`allkeys-lru`，最大内存可通过 `REDIS_MAXMEMORY` 调整

### MinIO 存储桶初始化

`minio-init` 服务在 MinIO 启动后自动执行：

1. 创建存储桶：`mail-attachments`（附件）、`mail-system`（系统文件）
2. 设置访问策略：均为 `private`（仅通过签名 URL 访问）
3. 生命周期规则：`orphaned/` 前缀文件 180 天后自动清理

---

## ✨ 功能清单

### 基础邮件功能
- ✅ 用户注册 / 登录（SHA-256 密码加密 + JWT 认证）
- ✅ 发送邮件（收件人、抄送、主题、正文、附件）
- ✅ 收件箱列表（分页、拉取、显示）
- ✅ 已发送邮件列表（分页）
- ✅ 邮箱详情查看（附件预览/下载）
- ✅ 标记已读 / 未读（列表实时显示已读状态）
- ✅ 删除邮件（软删除至垃圾箱）
- ✅ 批量删除邮件
- ✅ 垃圾箱管理（恢复、永久删除、批量删除、清空）
- ✅ 草稿箱（保存/编辑/发送/删除草稿）
- ✅ 回复邮件
- ✅ 转发邮件（自动引用原文、复制附件）
- ✅ 全文搜索（主题 + 正文，分页）
- ✅ 附件上传 / 下载 / 预览
- ✅ 分页控件（所有列表页）
- ✅ 新邮件实时推送通知（WebSocket + 轮询降级）
- ✅ 邮件状态双向同步端点
- ✅ Redis 缓存（邮件列表、未读数）
- ✅ 全局异常处理器（统一错误响应格式）

### 智能插件系统
- ✅ **垃圾邮件识别** — 关键词匹配 + 规则引擎
- ✅ **邮件优先级排序** — 多维度内容评分
- ✅ **恶意链接/伪造发件人检测** — URL 分析 + 发件人校验
- ✅ **智能摘要生成** — 规则提取 + LLM 增强，异步处理不阻塞
- ✅ **智能分类** — 关键词自动归类（工作/个人/财务等 8 类）
- ✅ **LLM 大模型集成** — 支持 OpenAI / DeepSeek 等兼容接口，智能摘要生成
- ✅ 可插拔架构 — 统一 `PluginInterface` 接口，前端一键启用/禁用

### 通信协议
- ✅ HTTP REST API（标准模式）
- ✅ WebSocket + STOMP 实时推送
- ✅ 自定义通信协议信封（`CustomProtocolEnvelope`，可选启用）
- ✅ 增量同步协议（`/mail/sync` 端点）
- ✅ **SMTP / IMAP 协议代理** — 传统邮件客户端（Thunderbird / Outlook / 手机 App）可直接收发，登录用本系统账号密码
- ✅ **外部投递** — 通过用户绑定的邮箱 SMTP，或系统级发信中继，投递到 QQ / 163 / Gmail 等外部地址
- ✅ **外部收信** — 定时轮询绑定邮箱的 IMAP 收件箱，或由 Cloudflare 推送本域地址的来信
- ✅ **收信去重** — `Message-ID` 唯一键为主，`(账户, IMAP UID)` 为兜底；重复触发同步不会产生重复邮件

### 两条获得邮箱的途径

想给外部邮箱发信、或接收外部来信，需要先在「邮箱账户」页拥有一个邮箱账户。
**「邮箱账户」页提供两种方式，区别只在于邮箱归谁：**

| | 绑定外部邮箱 | 领取本域地址 |
|---|---|---|
| 你要填 | 邮箱地址 + **授权码** | 只填一个用户名 |
| 邮箱地址 | 你已有的 QQ / 网易 / Gmail 邮箱 | 本系统域名下的新地址 |
| 需要管理员先做什么 | 无 | 配好 Cloudflare 与发信中继（见 [Cloudflare.md](Cloudflare.md)） |
| 收信方式 | 系统用 IMAP 登录你的邮箱拉取 | Cloudflare 收到后推送进来 |

两条路可以同时存在。系统会自动判断当前部署能用哪条 —— 没配 Cloudflare 时
「领取本域地址」入口不会显示，而不是让你点进去再报错。

> **同时拥有多个账户时用哪个发信？** 优先用你绑定的外部邮箱（那是你自己的身份），
> 没有可用的外部邮箱时才用本域地址。发件人地址必须是认证身份本身，
> 因此这个选择决定了收件人看到的 `From` 是谁。

---

#### 途径一：绑定外部邮箱（需要授权码）

**你只需要填两样东西：邮箱地址 + 授权码。** 服务器地址、端口、加密方式全部由系统自动确定。

**授权码不是登录密码。** 这是最常见的失败原因 —— 用登录密码连接会一直认证失败。各服务商对它的叫法不同（授权码 / 客户端授权密码 / 应用专用密码），但本质都是「为第三方客户端单独生成的一串密码」。

**一键绑定是怎么工作的**

1. **识别服务商** —— 输入邮箱地址后自动识别：先查内置的 17 个服务商预设，未收录则回退到 MX 记录反查（如 `exmail.qq.com` → 腾讯企业邮），再不行就按 `smtp.<域名>` / `imap.<域名>` 的常见命名猜测。
2. **展示授权码获取路径** —— 识别到服务商时，卡片上会直接列出该服务商的开启步骤和入口链接。未收录的域名不会给出这一步（系统不知道去哪儿拿，就不会假装知道）。
3. **真实连接探测** —— 按 SSL 优先后 STARTTLS 的顺序，最多尝试 3 组候选端点。**只有连得通的一侧才会落库**，避免存下一个从未验证过的服务器配置，在几分钟后的定时收信里才爆出错误。
4. **探测失败时** —— 回填失败原因 + 该服务商的具体操作路径。授权码里夹带空格（复制粘贴的常见副产物，Gmail 应用专用密码本身就是带空格展示的）会自动重试一次去掉空格的版本。

识别不出来、或想自己指定服务器时，对话框底部保留「手动配置」入口；每个已绑定账户的「修改」也走这条路。

**服务商速查表**（一键绑定用不上，它只是识别不到时的参考）

| 服务商 | SMTP（发信） | IMAP（收信） | 凭据获取方式 |
|---|---|---|---|
| QQ 邮箱 | `smtp.qq.com:465` SSL | `imap.qq.com:993` SSL | 网页版「设置 → 账户」开启 SMTP/IMAP 服务，按提示生成**授权码** |
| 163 / 126 | `smtp.163.com:465` SSL | `imap.163.com:993` SSL | 「设置 → POP3/SMTP/IMAP」开启服务后设置**客户端授权密码** |
| Gmail | `smtp.gmail.com:465` SSL | `imap.gmail.com:993` SSL | 先开启两步验证，再生成**应用专用密码**（账号密码无效） |

**这条路为什么绕不开授权码？** 因为系统要**登录你的邮箱**去读信，而邮箱服务商只认授权码，
不认别的东西 —— 这是服务商侧的认证要求，不是本系统的限制。所以这里做的是把
「填 9 个字段 + 自己查服务商文档」压缩成「填 2 个字段」，而不是假装可以不要凭据。

**不想要授权码的话，走下面第二条路。** 见 [Cloudflare.md](Cloudflare.md)。

**网易系（163 / 126 / yeah.net）的 Unsafe Login** —— 网易要求客户端在登录后发送 IMAP ID 命令，JavaMail 没有对应的配置属性，缺失时网易会直接回 `Unsafe Login. Please contact kefu@188.com`。系统已通过 `IMAP_SEND_CLIENT_ID`（默认开启）补上这一步。**不要关掉它**，关了 163 用户会立刻复现这个错误。

**其他注意事项**

- **发件人地址固定为绑定邮箱本身** —— QQ / 163 / Gmail 都会校验 `From` 与认证账号是否一致，不一致会被拒信或直接进垃圾箱。系统因此在代码层面固定了这个值，不做成可配置项。
- 首次同步默认只回溯 `IMAP_FETCH_DAYS`（7 天）、单轮最多 `IMAP_MAX_FETCH_PER_RUN`（200 封），避免一个用了多年的邮箱在第一次轮询时把库拉爆。之后按 UID 水位线增量拉取。
- 轮询间隔默认 3 分钟（`IMAP_SYNC_INTERVAL_MS`）。调小会增加服务商侧的连接频率，可能触发限流。
- 绑定后建议先点「测试连接」：SMTP 与 IMAP 分别测试，任一侧失败不影响另一侧的结果。
- 外部投递是异步的：接口返回成功只代表站内收件人已送达。外部那一路的结果会通过 WebSocket 推送（失败时弹出错误通知），也会写进「已发送」列表的投递状态列。

---

#### 途径二：领取本域地址（无需授权码）

填一个用户名，领到一个 `用户名@你的域名` 的地址。**全程没有授权码这一步。**

这不是"把授权码藏起来了"，而是压根不需要：Cloudflare 在本项目域名上直接
接收外部来信后推送给系统，系统从不登录任何人的邮箱 —— 发件人（QQ / Gmail / 什么都行）
在这里只是**发件方**，我们不需要持有他的任何凭据。

代价与前提：

- **需要管理员先完成部署**：域名托管在 Cloudflare 并开通 Email Routing。
  未部署时这个入口不会出现。
- **收信与发信是两条独立配置的链路**。只配了收信时，这个地址只能收不能发，
  界面上会明确标注「仅能收信」—— 收验证码、注册确认信这类场景完全够用。
- **地址是共享命名空间**：解绑后该地址会被释放，其他用户可以领走。

完整原理、部署步骤、DKIM 说明见 **[Cloudflare.md](Cloudflare.md)**；
Worker 的落地步骤见 **[deploy/cloudflare/README.md](deploy/cloudflare/README.md)**。

---

#### 途径三（可选）：用桌面/手机邮件客户端

以上两种账户都能通过 **SMTP/IMAP 协议代理**被传统邮件客户端访问：

| 配置项 | 值 |
|---|---|
| 用户名 | 你的**本系统账号邮箱**（登录 Web 端用的那个） |
| 密码 | 你的**本系统账号密码** |
| IMAP | 代理所在主机，端口 `143`，加密方式选**无** |
| SMTP | 代理所在主机，端口 `2525`，认证选 `PLAIN` 或 `LOGIN` |

代理没有自己的用户体系，登录凭据仍是本系统账号 —— 同样不涉及外部邮箱授权码。
支持的协议命令、已知限制与排障见 **[proxy/README.md](proxy/README.md)**。

### 前端特性
- ✅ Vue3 Composition API + Element Plus
- ✅ IndexedDB 本地缓存（stale-while-revalidate 策略）
- ✅ WebSocket 实时通知（失败自动降级为轮询）
- ✅ 邮件列表分页浏览

### 管理后台
- ✅ **用户管理** — 分页 + 关键字/角色/状态筛选，改角色、启用禁用、重置密码
- ✅ **反馈统计** — 分析准确率、按分类与来源（LLM / RULE）分组的采纳率，分歧样本可下钻
- ✅ **邮箱账户监控** — 所有用户的绑定状态、最近同步时间与失败原因，可手动触发同步或解绑
- ✅ **系统配置** — 系统默认 LLM 配置（密钥掩码回显）、插件开关、Prompt 模板与版本

**权限边界**：`AdminInterceptor` 对整个 `/admin/**` 与 `/plugin/**` 做管理员校验；前端路由守卫的
`requiresAdmin` 只是体验优化（不给普通用户展示点了会 403 的入口），改前端绕不过后端。

**保护措施**：不能给自己降级、不能禁用或降级最后一个管理员、重置密码后强制其下次登录改密。
禁用账号或重置密码会同时吊销该用户已签发的 Token，立即生效而不是等到 Token 自然过期。

### 基础设施
- ✅ Docker Compose 一键部署（8 个服务组件）
- ✅ 所有组件 Docker 内构建，无需本地环境
- ✅ Redis 缓存（邮件列表、未读数）
- ✅ MinIO 对象存储（大附件，支持持久化与扩容）
- ✅ Nginx 反向代理 + 静态资源 + WebSocket 代理
- ✅ MyBatis-Plus 分页插件

---

## 📊 数据库表说明

| 表名 | 说明 |
|------|------|
| `user` | 用户表（邮箱、密码、昵称、角色、状态、强制改密标记、登录审计） |
| `mail` | 邮件表（发件人、收件人、抄送、主题、正文、优先级、垃圾标记、摘要、分类、方向、外部收发字段） |
| `attachment` | 附件表（文件名、存储路径、大小） |
| `mail_status` | 邮件状态表（已读、删除、同步状态、时间追踪） |
| `plugin_config` | 插件配置表（开关状态） |
| `llm_config` | LLM 大模型配置表（API 端点、密钥密文、模型名、归属用户） |
| `mail_account` | 邮箱账户表（两条途径共用；`provider_type` 区分外部邮箱/本域地址，前者存 SMTP/IMAP 服务器与授权码密文，后者靠 `uk_cloudflare_address` 保证一个地址只被一个用户领取） |
| `mail_intelligence_result` | 按 (邮件, 收件人) 的分析结果（分类、垃圾标记、优先级、风险、来源 LLM/RULE） |
| `user_feedback` | 用户对分析结果的人工反馈（认可/纠正、修正分类、备注） |
| `llm_call_log` | LLM 调用日志（延迟、token 数、状态、错误），供管理端查看成本与失败率 |
| `prompt_template` | Prompt 模板与版本（分析时记录所用版本号） |

> **首次部署**：`init.sql` 自动建表并写入默认配置，无需手工干预。
>
> **已存在的库（v1 / v2）**：需手工执行迁移脚本，按顺序
> `migration_v2.sql` → `migration_v3.sql` → `migration_v4.sql`。
> ⚠️ 这些脚本都使用普通 `ALTER TABLE`，**重复执行会报"列已存在"错误**，执行前请确认当前库的版本。

---

## 🛠️ 技术栈

### 前端
- Vue 3 (Composition API)
- Element Plus 2.x
- Pinia 状态管理
- Vue Router 4
- Axios HTTP 客户端
- Vite 构建工具

### 后端
- SpringBoot 2.7.x
- MyBatis-Plus 3.5.x
- JWT (jjwt 0.9.1)
- MySQL Connector
- Spring Data Redis + Lettuce 连接池
- MinIO Client 8.5.x
- Spring Async

### 基础设施
- Docker + Docker Compose
- Nginx 1.25 (Alpine)
- MySQL 8.0
- Redis 7 (Alpine)
- MinIO (对象存储)

---

## 🔍 部署验证步骤

### 1. 验证所有容器运行正常

```bash
docker-compose ps
# 所有服务状态应为 "Up" 或 "healthy"（frontend 为 "exited (0)"）
```

### 2. 验证前端页面

```bash
# 浏览器访问
curl -f http://localhost/
# 应返回 Vue3 SPA 的 HTML 页面
```

### 3. 验证 Redis 部署成功

```bash
docker exec mail-redis redis-cli -a $REDIS_PASSWORD ping
# 预期输出: PONG

# 验证持久化 — 重启后数据仍在
docker-compose restart redis
sleep 5
docker exec mail-redis redis-cli -a $REDIS_PASSWORD GET test_key
```

### 4. 验证 MinIO 部署成功

```bash
curl http://localhost:9000/minio/health/live
# 预期输出: 200 OK

# 访问 MinIO 控制台 http://localhost:9001
# 使用 .env 中的 MINIO_ROOT_USER / MINIO_ROOT_PASSWORD 登录
```

### 5. 验证后端 API 可用

```bash
# 测试注册接口
curl -X POST http://localhost/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"123456","nickname":"测试用户"}'
```

---

## ⚠️ 注意事项

1. **唯一前置条件**：只需安装 Docker + Docker Compose，无需 JDK / Node.js / Maven / MySQL / Redis / MinIO
2. **首次启动**：数据库初始化由 `deploy/mysql/init.sql` 自动执行，MinIO 存储桶由 `minio-init` 服务自动创建
3. **生产环境密码管理**：
   - 修改 `.env` 中所有密码（`MYSQL_ROOT_PASSWORD`、`REDIS_PASSWORD`、`MINIO_ROOT_PASSWORD`、`JWT_SECRET`）
   - 禁止使用默认密码或弱密码
   - 建议使用 `openssl rand -hex 32` 生成 32 位以上随机字符串
   - `.env` 文件不应提交到版本控制系统（已在 `.gitignore` 中排除）
4. **MinIO 权限**：存储桶默认为 `private`，附件下载通过签名 URL 实现，不要将存储桶设为 `public`
5. **Nginx 配置**：生产环境需替换 `server_name` 为实际域名；MinIO 控制台代理可注释或添加 IP 白名单
6. **JVM 内存参数**：后端 `-Xms256m -Xmx512m`，根据服务器配置在 `backend/Dockerfile` 中调整
7. **数据持久化**：所有持久化数据（MySQL、Redis、MinIO）使用 Docker 命名卷，可通过 `docker volume ls` 查看
8. **前端更新**：修改前端代码后运行 `docker-compose up -d --build frontend` 重新构建

---

> 📮 项目地址: `d:\EMAILSYSTEM\private\email-system`  
> 📅 开发日期: 2026-05  
> 🏫 2026 软件开发综合实训
