# Cloudflare 入站收信部署

把"发往本项目域名的邮件"接到本系统里来。做完这一步，用户就能领一个
`xxx@你的域名` 的地址收信，**全程不需要任何授权码**。

原理与整体设计见仓库根目录的 [Cloudflare.md](../../Cloudflare.md)；
这里只讲怎么落地。

---

## 0. 前置条件

| 需要什么 | 说明 |
|---|---|
| 一个自己的域名 | 必须已托管在 Cloudflare（域名的 NS 记录指向 Cloudflare）。Email Routing 只对 Cloudflare 托管域开放 |
| 后端公网可达 | Worker 要能 `POST` 到你的后端。**必须是 HTTPS 的公网地址** —— Worker 跑在 Cloudflare 边缘，访问不到你的 `localhost` |
| Node.js 18+ | 用于 `wrangler` 命令行 |
| Cloudflare 账号 | 免费版即可，Email Routing 不收费 |

> 本地开发想联调？用 `cloudflared tunnel` 或 `ngrok` 把本地 8080 暴露成一个临时 HTTPS 地址，填到 `BACKEND_INBOUND_URL` 里即可。

---

## 1. 开通 Email Routing

1. Cloudflare Dashboard → 计算 → 电子邮件服务 → 电子邮件路由 → 接入域名
2. 点 **Enable Email Routing**。Cloudflare 会自动往你的 DNS 里加 MX 与 SPF 记录
   （`route1/2/3.mx.cloudflare.net` 之类）
3. 如果提示与已有的 MX 记录冲突（比如域名上原本挂着企业邮箱），需要先决定
   这个域名给谁用 —— 同一个域名不能同时把 MX 指向两套系统

> ⚠️ **建议用一个子域名专门做本系统的邮箱**（如 `mail.example.com`），
> 而不是主域名。主域名上通常已经有别的邮箱在用了。

---

## 2. 部署 Worker

**（1）登录 wrangler，授权 Cloudflare 账号**
```bash
cd deploy/cloudflare
npm install
npx wrangler login          # 浏览器里授权一次
```

**（2）写入共享密钥**（这一步不能省，也不要写进 `wrangler.toml`）：

```bash
# 生成一个随机密钥
openssl rand -hex 32

# 写入 Cloudflare（加密存储，不会出现在部署产物里）
npx wrangler secret put INBOUND_SHARED_SECRET
粘贴上一步生成的值
```

**（3）改 `wrangler.toml` 里的后端地址 BACKEND_INBOUND_URL**：

**a. 安装 cloudflared（Windows）**

以管理员打开 PowerShell，执行：

```bash
winget install --id Cloudflare.cloudflared
```

安装完**关闭旧 PowerShell，新开一个 PowerShell 窗口**。

**b. 启动隧道（新开独立 PowerShell 窗口，不要关闭这个窗口！关了地址就失效）**

```bash
# 内网穿透
cloudflared tunnel --url http://localhost
```

运行成功，终端输出类似：

```
https://happy‑sun‑123.trycloudflare.com
```

复制这串`https://xxx.trycloudflare.com`，这就是你的公网根地址！

**c. 拼接完整 BACKEND_INBOUND_URL**

规则：`公网地址 + /api/inbound/cloudflare`
例子：

```
https://happy‑sun‑123.trycloudflare.com/api/inbound/cloudflare
```
> 这个完整链接，才填进 `wrangler.toml` 的`BACKEND_INBOUND_URL`。
> 路径必须以 `/inbound/cloudflare` 结尾，前面可以带 Nginx 的 `/api` 前缀，只要它最终能转发到后端的 `/inbound/cloudflare`。


**（4）部署**：

```bash
npm run deploy
```

---

## 3. 把邮件路由到 Worker

在Cloudflare Dashboard中的电子邮件路由规则中：

- 想接住所有地址（推荐，用户才能自由领地址）：
  启用 **Catch-all address（全收）** → 点击**编辑**， 选 **发送到Worker** → 选 `mail-system-inbound`
- 只想接某几个固定地址：**Custom addresses** 里逐个添加，同样指向这个 Worker

> Catch-all 与 Custom addresses 可以共存。Catch-all 是"其余全收"，
> 自定义地址优先级更高。

---

## 4. 配置后端

在 `.env.example` 里加上：

```bash
# 开启入站接收。不开启时 /inbound/** 一律返回 503
INBOUND_ENABLED=true

# 与 Worker 侧完全一致的共享密钥（第 2 步生成的那个）
INBOUND_SHARED_SECRET=你生成的密钥

# 允许收信的域名，逗号分隔。用户只能领取这些域名下的地址
# ⚠️ 必须与第 1 步在 Cloudflare 上配了 MX 的域名一致，否则信根本到不了
INBOUND_DOMAINS=mail.example.com
```

重启后端：

```bash
cp .env.example .env  
docker-compose up -d backend
```

---

## 5. 验证

**① 后端认不认这个配置**

登录后打开「邮箱账户」页，工具栏里应该多出一个 **「领取本域地址」** 按钮，弹窗里的域名下拉框有你配置的域名。

**② 收一封信**

1. 在该区块领一个地址，比如 `alice@mail.example.com`
2. 用你平时的邮箱（QQ / Gmail 都行）往这个地址发一封信
3. 观察两处日志：

```bash
# Worker 侧：能看到这次调用与转发结果
cd deploy/cloudflare && npm run tail

# 后端侧：成功时是这一行
docker-compose logs -f backend | grep Inbound
# [Inbound] alice@mail.example.com 收到来自 xxx@qq.com 的邮件，mailId=123
```

**③ 发一封信**

本域地址的收信已经通了，但**发信还需另外配置发信中继**（Cloudflare
Email Routing 只负责收，不提供发信服务）。见根目录
[Cloudflare.md](../../Cloudflare.md) 的「二、发信：项目级中继」一节。

---

## 排障

| 现象 | 原因与处置 |
|---|---|
| 用户根本没收到信，Worker 也没有日志 | MX 记录没生效或域名写错。`dig MX mail.example.com` 应指向 `*.mx.cloudflare.net` |
| Worker 日志：`签名校验失败` / HTTP 401 | `INBOUND_SHARED_SECRET` 两边不一致。重新 `wrangler secret put` 并同步 `.env` 后重启后端 |
| Worker 日志：`无法连接后端` | `BACKEND_INBOUND_URL` 不可达。Worker 走公网，`localhost` 一定不通 |
| 后端日志：`无归属地址` | 这个地址还没被任何用户领取，或已被停用。界面里领一个即可 |
| 后端日志：`签名校验失败` | 见上一条 401。另外检查服务器时间 —— 签名带 5 分钟新鲜度窗口，系统时钟偏差过大会被判过期 |
| 后端日志：`MIME 解析失败` | 这封信本身畸形。后端会返回 5xx，Cloudflare 重试若干次后**退信给发件人**，不会静默丢信 |
| 后端返回 503 | `INBOUND_ENABLED` 没开，或 `INBOUND_SHARED_SECRET` 没配 |
| 收到的信没有正文 | 发件方只发了 HTML 且结构异常。正文在收信时由 HTML 转成纯文本，转换失败的极端情况会为空 |

**想看某个地址为什么收不到信**，按这个顺序查：

```
Cloudflare Email Routing 的活动日志（是否收到）
  → Worker 日志 npm run tail（是否转发成功、后端返回了什么）
    → 后端日志 grep Inbound（地址是否有归属）
      → 数据库 SELECT * FROM mail_account WHERE email_address = '...'
```

---

## 这几个文件是干什么的

| 文件 | 作用 |
|---|---|
| `src/index.js` | Worker 本体：读原始报文 → HMAC 签名 → 转发给后端 |
| `wrangler.toml` | 部署配置。**密钥不要写在这里**，用 `wrangler secret put` |
| `package.json` | 只是为了 `wrangler` 这个命令，运行时没有任何 npm 依赖 |

Worker 侧刻意不做任何 MIME 解析 —— 解析只写在后端一处（`MimeParser`），
两边各写一份必然在某个边界条件上分叉，而那种问题极难定位。
