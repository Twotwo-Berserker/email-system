# 📧 邮件系统 — 2026 软件开发综合实训

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
# 1. 复制环境变量模板并修改（特别是密码）
cp .env.example .env

# 2. 构建并启动所有服务
docker-compose up -d

# 3. 查看服务运行状态
docker-compose ps

# 4. 查看各服务日志
docker-compose logs -f
```

### 访问地址

| 服务 | 地址 | 说明 |
|------|------|------|
| 前端页面 | `http://localhost` | Nginx → 前端静态资源 |
| 后端 API | `http://localhost/api/` | Nginx → Backend (8080) |
| MinIO 控制台 | `http://localhost:9001` | 对象存储管理界面 |

---

## 🐳 架构与部署

### 整体架构

```
                         ┌──────────────────┐
                         │   Nginx :80      │  反向代理 + 静态资源
                         │ deploy/nginx/    │
                         └────────┬─────────┘
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

### 启动流程

1. **MySQL / Redis / MinIO** 先启动并完成健康检查
2. **Backend** 等待 MySQL、Redis、MinIO 就绪后启动
3. **Frontend** 在 Docker 内执行 `npm ci` → `npm run build`，产物写入 `frontend-dist` 命名卷
4. **Nginx** 等待 Frontend 构建完成 + Backend 就绪后启动，挂载 `frontend-dist` 卷提供静态资源
5. **MinIO-Init** 在 MinIO 就绪后执行一次，创建存储桶

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
│       ├── plugin/                  # 智能插件系统
│       └── util/                    # 工具类
├── frontend/                        # Vue3 前端
│   ├── Dockerfile                   # 前端 Docker 镜像（Node 构建 → 产物导出）
│   ├── package.json
│   ├── vite.config.js
│   └── src/
│       ├── api/                     # API 封装
│       ├── components/              # 可复用组件
│       ├── views/                   # 页面视图
│       ├── router/                  # 路由
│       ├── stores/                  # Pinia 状态管理
│       └── layouts/                 # 布局
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
│       └── init.sql                 # 数据库建库脚本
├── doc/API.md                       # 接口文档
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
- ✅ 收件箱列表（拉取、显示）
- ✅ 邮件详情查看
- ✅ 标记已读 / 未读
- ✅ 删除邮件（软删除至垃圾箱）
- ✅ 全文搜索（主题 + 正文）
- ✅ 附件上传 / 下载 / 预览
- ✅ 新邮件到达提示（30 秒轮询未读数量）

### 智能插件系统
- ✅ **垃圾邮件识别** — 关键词匹配 + 规则引擎
- ✅ **邮件优先级排序** — 多维度内容评分
- ✅ **恶意链接/伪造发件人检测** — URL 分析 + 发件人校验
- ✅ **智能摘要生成** — 异步 HTML 清洗 + 截取
- ✅ **智能分类** — 关键词自动归类（工作/个人/财务等 8 类）
- ✅ 可插拔架构 — 统一 `PluginInterface` 接口
- ✅ 开关控制 — 前端设置页一键启用/禁用

### 基础设施
- ✅ Docker Compose 一键部署（7 个服务组件）
- ✅ 所有组件 Docker 内构建，无需本地环境
- ✅ Redis 缓存（邮件列表、验证码、Token 黑名单）
- ✅ MinIO 对象存储（大附件，支持持久化与扩容）
- ✅ Nginx 反向代理 + 静态资源

---

## 📊 数据库表说明

| 表名 | 说明 |
|------|------|
| `user` | 用户表（邮箱、密码、昵称） |
| `mail` | 邮件表（发件人、收件人、抄送、主题、正文、优先级、垃圾标记、摘要、分类） |
| `attachment` | 附件表（文件名、存储路径、大小） |
| `mail_status` | 邮件状态表（已读、删除、同步） |
| `plugin_config` | 插件配置表（开关状态） |

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
