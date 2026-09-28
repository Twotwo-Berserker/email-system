# 用 Resend 打通本域邮箱发信（项目级中继）

本文只讲一件事：**让本系统域名下的地址（如 `alice@twotwomail.dpdns.org`）能对外发信**，
采用 Resend HTTP API 这条通道，需要准备什么、每步为什么、怎么验证。

收信链路（Cloudflare Email Routing + Email Worker）见 [Cloudflare.md](Cloudflare.md) 与
[deploy/cloudflare/README.md](deploy/cloudflare/README.md)，两者互不依赖：
**只配了收信时，本域地址是"能收不能发"的**，这是一个合法状态，界面会明确标注。

---

## 0. 一句话结论

需要准备 4 样东西：

| # | 准备项 | 落在哪里 | 不做会怎样 |
|---|---|---|---|
| 1 | 在 Resend 后台**验证发信域名**（DNS 记录） | Cloudflare DNS | 发信被收件方拒收或直接判垃圾邮件 |
| 2 | 创建 **API Key** | Resend 后台 | `isAvailable()` 返回 false，本域地址发不出信 |
| 3 | `OUTBOUND_TRANSPORT=resend` + `RESEND_API_KEY=re_xxx` | `.env` → 容器环境变量 | 同上 |
| 4 | 重启后端使配置生效 | `docker compose up -d backend` | 配置读了旧的，改了跟没改一样 |

---

## 1. 先搞清楚：这封信的 `From` 到底是谁

整条链路里唯一不能自由变动的东西就是 `From`，它决定了第 1 步要给**哪个域名**做验证。
代码里它是这样定下来的：

```
MailAccount.emailAddress（用户领取/绑定的地址）
  → MailSendServiceImpl.buildMessage()  message.setFromAddress(account.getEmailAddress())
  → OutboundMessage.fromHeader()        "显示名 <alice@twotwomail.dpdns.org>"
  → OutboundRelayServiceImpl.sendViaResend()  payload["from"]
```

参考 [MailSendServiceImpl.java:139-160](backend/src/main/java/com/mailsystem/service/impl/MailSendServiceImpl.java#L139-L160)、
[OutboundMessage.java:57-67](backend/src/main/java/com/mailsystem/dto/OutboundMessage.java#L57-L67)。

由此得到两条硬结论：

1. **必须在 Resend 验证的域名 = `INBOUND_DOMAINS` 里的每一个域名。**
   用户领的地址只能落在这些域名下（[InboundProperties.java](backend/src/main/java/com/mailsystem/config/InboundProperties.java) 的 `isLocalDomain()`），
   所以 `INBOUND_DOMAINS=a.com,b.com` 就要求 a.com 和 b.com 都在 Resend 里验证通过。
   当前 `.env` 里是 `INBOUND_DOMAINS=twotwomail.dpdns.org` —— **要验证的就是这个域名**。
2. **`From` 不可能做成"随便填一个 Resend 账号"**。代码写死为账户自己的地址，
   因为 Resend 与各大邮箱都会校验 `From` 与认证身份是否一致，不一致会被拒信。
   显示名（`displayName`）可以随便改，不影响校验。
   （这意味着 `onboarding@resend.dev` 那种测试发件地址在本项目里用不上。）

### 一个顺带的好处：回信能收得到

本域地址发出的信，收件人点"回复"是回到 `alice@twotwomail.dpdns.org` ——
这个域名的 MX 已经指向 Cloudflare Email Routing，收信链路已通。
**所以第一、二步（收信）做完之后再来做发信，才算真正闭环。**

---

## 2. 第一步：在 Resend 验证发信域名

### 2.1 加域名

1. 注册/登录 [resend.com](https://resend.com)，创建一个 **Team**（个人用就叫自己名字）。
2. 左侧 **Domains → Add Domain**，填 `twotwomail.dpdns.org`。
3. **Region 选择要记下来**（默认 `us-east-1`）—— 后面 DNS 记录里的主机名必须与它一致，
   选错会得到 `region-mismatch` 报错。
4. Resend 会列出 3~4 条 DNS 记录。

> ⚠️ 本项目**只发信、不收信**，不需要配 Resend 的 Inbound（`inbound-smtp.*` 那条 MX）——
> 收信已经由 Cloudflare 负责了，再配一遍是两套系统抢同一个域名的邮件路由。

### 2.2 要往 Cloudflare DNS 里加什么

以 `us-east-1` 为例（**主机名只填子域部分**，Cloudflare 会自动补上你的域名）：

| 类型 | 主机名 | 内容 | 优先级 | 作用 |
|---|---|---|---|---|
| MX | `send` | `feedback-smtp.us-east-1.amazonses.com` | `10` | SPF 的返回路径（退信回收） |
| TXT | `send` | `v=spf1 include:amazonses.com ~all` | — | 声明"这个子域的合法发信源" |
| TXT | `resend._domainkey` | `p=<Resend 生成的一长串公钥>` | — | DKIM 签名公钥，**必须原样复制** |
| TXT（可选，建议加） | `_dmarc` | `v=DMARC1; p=none;` | — | 先以 `p=none` 观察，别一上来就 `reject` |

注意事项：

- **主机名不要带自己的域名**。填 `send`，不要填 `send.twotwomail.dpdns.org`；
  填 `resend._domainkey`，不要填全名。多数 DNS 面板会自动追加域名，重复追加会导致记录不生效。
- **新账号可能看到的是 CNAME 记录而不是上面的 TXT+MX**（Resend 换过验证方式）。
  按后台实际显示的填即可 —— 但 CNAME 在 Cloudflare 上**必须关掉代理（灰云 / DNS Only）**，
  橙云会让 Resend 永远验证不过，且一次失败可能要等数小时才能重试。
- **两条记录的优先级不要撞**。`send` 那条 MX 若与已有记录同为 10，改成 20 或 30。
- **别动 apex（`@`）上已有的记录**。Cloudflare Email Routing 给你加的是 apex 的 MX
  和一条 apex SPF，那是收信用的，删了收信就断。本次新增的记录全部落在 `send.`
  和 `resend._domainkey.` 两个子域上，与它们不冲突 —— 这也是 Resend 用子域 + DKIM
  `d=` 对齐的原因，`From` 依然是 apex 域名，DKIM 宽松对齐即可通过 DMARC。

### 2.3 等验证通过

- 点 **Verify DNS Records**，多数在 15 分钟内通过，最慢 72 小时。
- 反复失败时用 `dig`（或 Cloudflare 的 DNS 检查工具）确认记录已经真的在公网上，
  而不是"在面板里存着"。
- **验证通过之前不要往下走**。此时用这个域名发信，收件方会拒收或直接丢垃圾箱，
  而项目里看到的失败原因会是 Resend 返回的原文（如 `domain is not verified`）——
  [OutboundRelayServiceImpl.java:174-179](backend/src/main/java/com/mailsystem/service/impl/OutboundRelayServiceImpl.java#L174-L179)
  会把这段原文带出来，不会只给一个 HTTP 状态码。

> 关于 `dpdns.org` 这类免费子域：如果验证长时间不过，先看 Resend 报错原文再判断，
> 不要凭猜测反复重加记录。Resend 侧的原始报错会被写进「已发送」的投递状态里。

---

## 3. 第二步：创建 API Key

1. **API Keys → Create API Key**。
2. 权限选 **Sending access**（不要选 Full access）——本项目只需要发信这一件事，
   一旦这个 Key 泄漏，Full access 还能改域名配置、读统计。可以在同页把 Key
   限制到 `twotwomail.dpdns.org` 这一个域名。
3. 复制 `re_` 开头的 Key。**它只显示一次。**

> Key 落在 `.env` 里，而 `.env` 已被 `.gitignore` 忽略（见 [.gitignore:7](.gitignore#L7)），
> 不会进版本库。这是它相对 `wrangler.toml` 的 `[vars]` 的差别 —— 但 `.env` 在磁盘上是明文的，
> 生产机上要注意文件权限。

---

## 4. 第三步：配置本项目

### 4.1 改 `.env`

```dotenv
# 通道二选一：resend
OUTBOUND_TRANSPORT=resend

# 上一步拿到的 Key
RESEND_API_KEY=re_xxxxxxxxxxxxxxxxxxxx

# 可选：默认 https://api.resend.com/emails，一般不用改
# RESEND_ENDPOINT=https://api.resend.com/emails
```

配置项的解析链是 `.env` → [docker-compose.yml:139-140](docker-compose.yml#L139-L140) → 容器环境变量 →
[application.yml](backend/src/main/resources/application.yml) 的 `app.outbound.*` →
`OutboundRelayServiceImpl` 的 `@Value` 注入。

**三处要同时成立**，只改一处不生效：

1. `.env` 里有值；
2. `docker-compose.yml` 里该变量被透传进容器（这两个已经在了，不用动）；
3. 后端**重启**（环境变量只在进程启动时读取）。

### 4.2 重启

```bash
docker compose up -d backend
docker compose logs -f backend | head -50
```

就绪判据（[OutboundRelayServiceImpl.java:102-111](backend/src/main/java/com/mailsystem/service/impl/OutboundRelayServiceImpl.java#L102-L111)）：

```
isAvailable() == (transport == "resend" && RESEND_API_KEY 非空)
```

任一不满足，本域地址发信时会在**写库之前**被拦住并报"本实例未配置发信中继"，
而不是先落库、几十秒后再异步失败 —— 见 [MailServiceImpl.java:675-693](backend/src/main/java/com/mailsystem/service/impl/MailServiceImpl.java#L675-L693)。
所以"页面上根本没出现发送失败、但信也没出去"的情况在这个环节不会发生：连"发送"都点不下去。

---

## 5. 第四步：验证

按"由外到内"分三层，**任何一层失败都不要再往下走**，否则会把中继的问题误判成应用的问题。

### 5.1 只验证中继（不碰本项目）

```bash
curl -X POST https://api.resend.com/emails \
  -H "Authorization: Bearer re_xxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{
    "from": "alice@twotwomail.dpdns.org",
    "to": ["你的QQ号@qq.com"],
    "subject": "Resend 通道自测",
    "text": "如果你在收件箱（不是垃圾箱）里看到这封信，DKIM/SPF 就通了。"
  }'
```

- 返回 `{"id":"..."}` 且 QQ 收件箱里收到 → 中继侧完全就绪，问题若在后面一定是本项目配置问题。
- 返回 403 / `domain is not verified` → 回到第 2 步。
- 返回 401 → Key 错或权限不足。
- 返回 429 → 触到配额/频率，见第 7 节。
- **收到但进垃圾箱** → DNS 记录没全（多半缺 DKIM）或 DMARC 配错，回第 2 步。新域名首次发信被判垃圾邮件也常见，先把记录配齐再判断。

### 5.2 验证本项目的配置已读到

`GET /mail-account/capabilities`（前端「邮箱账户」页会调它，见
[MailAccountController.java:129](backend/src/main/java/com/mailsystem/controller/MailAccountController.java#L129)）：

```bash
curl -H "Authorization: Bearer <用户JWT>" http://localhost:8080/mail-account/capabilities
```

期望：

```json
{
  "inboundEnabled": true,
  "domains": ["twotwomail.dpdns.org"],
  "outboundTransport": "resend",
  "outboundAvailable": true,
  "message": "可领取本系统域名下的地址。收信无需授权码；发信通过本系统的中继（resend）"
}
```

`outboundAvailable` 仍为 `false` → `RESEND_API_KEY` 没进到容器里：
`docker compose exec backend env | grep RESEND` 立刻能看出来。

### 5.3 端到端：从界面上发一封到 QQ 邮箱

1. 「邮箱账户」页确认已经有本域地址（没有就领一个 `用户名@twotwomail.dpdns.org`）。
   若同时绑定了 QQ 邮箱账户，注意**本域地址要成为默认发信账户**，否则会用 QQ 那条通道发出去
   （见 [MailSendServiceImpl.java:109-122](backend/src/main/java/com/mailsystem/service/impl/MailSendServiceImpl.java#L109-L122) 的选账户逻辑）。
2. 写信，收件人填 `xxx@qq.com`，发送。
3. 看三处：
   - 界面「已发送」的投递状态（`mail.external_status` = `SENT` / `FAILED`，失败原因在
     `mail.external_error`，截断 500 字符）；
   - 后端日志里的 `[MailSend] 邮件#N 已通过 ... 投递给 ...` 或 `[MailSend] ... 外发失败`；
   - Resend 后台的 **Emails/Logs**，能看到同一封信的投递结果与事件。

投递是异步的：`AFTER_COMMIT` + `@Async`（[MailDeliveryListener.java:42-44](backend/src/main/java/com/mailsystem/event/MailDeliveryListener.java#L42-L44)），
所以点完"发送"界面会先提示成功（站内投递确实成功了），外部结果稍后才由状态与
WebSocket 通知体现 —— 失败时一定会弹窗，不需要自己去翻「已发送」。

### 5.4 反向验证：回信能进来

在 QQ 邮箱里直接回复那封信。回到的地址就是本域地址，应当出现在本系统收件箱。
这一步通过，说明"发得出去、也收得回来"，本域邮箱才算真正可用。

---

## 6. 失败原因对照表

代码在收到非 200 时会去响应体里取 `message` 或 `error` 字段，取不到就原样返回
（[OutboundRelayServiceImpl.java:187-206](backend/src/main/java/com/mailsystem/service/impl/OutboundRelayServiceImpl.java#L187-L206)），
所以**「已发送」里的失败原因基本就是 Resend 的原话**，照下表处理：

| 失败原因（原文） | 原因 | 处置 |
|---|---|---|
| `domain is not verified` | 域名未验证 / 验证记录被人删了 | 回第 2 步，看 Resend 后台的验证状态 |
| `The <domain> domain is not verified` + 刚加完记录 | 记录还没传播 | 等，最多 72 小时；用 `dig` 确认已在公网 |
| HTTP 401 / `API key is invalid` | Key 错、被删、或权限不是 Sending | 重建 Key，改 `.env`，重启后端 |
| HTTP 403 + `not authorized to send from this domain` | Key 被限制到了别的域名 | 改 Key 的域名范围，或换 Key |
| `region-mismatch` | MX 记录的 region 与后台选的不一致 | 改成同一个 region |
| HTTP 429 `daily_quota_exceeded` | 免费版每天 100 封（UTC 日，0 点重置） | 等次日 UTC 0 点；**代码没有重试，429 直接算失败** |
| HTTP 429 `monthly_quota_exceeded` | 免费版每月 3000 封 | 升级套餐或减少外发 |
| `Invalid \`to\` field` | 收件地址格式问题 | 检查收件人里有没有杂字符 |
| `Request Entity Too Large` / 附件丢失 | 编码后总大小超 40MB | 见第 7 节附件说明 |
| 读超时 / `Read timed out` | 附件太大，20 秒内没上传完 | 调大 `SMTP_READ_TIMEOUT_MS` 后重启 |
| `本系统未配置发信中继（OUTBOUND_TRANSPORT=none）` | 配置根本没生效 | 检查 `.env` → compose 透传 → 是否重启 |

---

## 7. 配额、限制与需要提前知道的取舍

| 项目 | 值 | 对本项目的含义 |
|---|---|---|
| 免费额度 | 3000 封/月、100 封/天 | 日配额按 **UTC 日历日**重置；团队自用够，对外提供服务要提前算 |
| 计费口径 | **每个 `To`/`CC` 收件人各算一封** | 一次发 5 个人 = 扣 5 封。本项目 `payload["to"]` 是数组，一次调用仍按人数计 |
| 频率限制 | 默认 10 req/s | 远高于本系统的并发，不构成瓶颈 |
| 已验证域名数 | 免费版 1~3 个（不同来源说法不一，以 Resend 后台实际提示为准） | `INBOUND_DOMAINS` 里的域名数量别超过这个数，否则会有域名永远验证不上 |
| 单封大小 | **编码后** 40MB | 附件走 base64 会膨胀约 33%；本项目上传上限是 50MB（`FILE_UPLOAD_MAX_SIZE`），**可能存在"能上传、发不出去"的组合**，注意这个错位 |
| 无重试 | 本项目代码不重试 | 429 / 5xx / 超时都会直接记为 `FAILED`，需要人工重发。这也是"发送前先拦住"设计存在的原因 |
| 正文格式 | 代码用 `text` 而非 `html` 字段 | 库里的正文在收信时已转成纯文本（[OutboundRelayServiceImpl.java:151-153](backend/src/main/java/com/mailsystem/service/impl/OutboundRelayServiceImpl.java#L151-L153)），发出的信是纯文本，不是富文本 |

超时说明：HTTP 超时**复用** `app.smtp.connect-timeout-ms` / `read-timeout-ms`
（[OutboundRelayServiceImpl.java:85-89](backend/src/main/java/com/mailsystem/service/impl/OutboundRelayServiceImpl.java#L85-L89)），
默认 10s / 20s。这是"外发一次网络往返"的预算，与通道无关 ——
改它会同时影响账户 SMTP 与中继，调之前想清楚。

---

## 8. 上线前检查清单

- [ ] Resend 里该域名状态是 **Verified**（不是 Pending）
- [ ] `dig TXT resend._domainkey.twotwomail.dpdns.org` 能查到 `p=...`
- [ ] `dig MX send.twotwomail.dpdns.org` 能查到 `feedback-smtp.<region>.amazonses.com`
- [ ] 这些新记录在 Cloudflare 上是**灰云（DNS Only）**，没被代理
- [ ] apex 上 Email Routing 的 MX 记录**还在**（收信没被自己搞坏）
- [ ] API Key 权限是 Sending access，且限定到本项目域名
- [ ] `.env` 里 `OUTBOUND_TRANSPORT=resend`、`RESEND_API_KEY` 非空
- [ ] 后端已重启，`GET /mail-account/capabilities` 返回 `outboundAvailable: true`
- [ ] 5.1 的 curl 能发到 QQ **收件箱**（不是垃圾箱）
- [ ] 从 Web 界面发一封到 QQ，状态为 `SENT`
- [ ] 从 QQ 直接回复，本系统收件箱能收到

---

## 9. 两个必须知道的安全问题

### 9.1 仓库里的 `.env.example` 含真实密钥（已进 git 历史）

`.env.example` 是**被 git 跟踪**的，且当前与 `.env` **逐字节相同** ——
真实的 `INBOUND_SHARED_SECRET` 已经进了提交历史。
改掉 `.env.example` 里的值只能防止未来泄漏，**历史提交里的那个值仍然有效**，
必须两边同时轮换（`openssl rand -hex 32` 重新生成，Worker 侧用
`wrangler secret put INBOUND_SHARED_SECRET` 同步），否则 `.env.example` 的规范做法形同虚设。

同理，**`RESEND_API_KEY` 千万不要写进 `.env.example`** —— 它是付费资源，
泄漏后别人可以用你的域名和额度发信。

### 9.2 发信不是匿名的

发出的信 `From` 就是用户领取的本域地址，`Return-Path` 落在 `send.<域名>`。
本域地址是实名可追溯的（能对应到领取它的账号），对外发滥用邮件会直接影响
整个域名的信誉 —— 一旦域名被拉黑，**所有用户**的信都进垃圾箱。
如果要对外提供服务，考虑给外发加频率限制。

---

## 10. 备选：不用 Resend 的情况

同一条中继逻辑还有 SMTP 通道（`OUTBOUND_TRANSPORT=smtp`），
适合"已有自己的邮件服务器"或"用企业邮箱的 SMTP 中继"：

```dotenv
OUTBOUND_TRANSPORT=smtp
RELAY_SMTP_HOST=smtp.example.com
RELAY_SMTP_PORT=587
RELAY_SMTP_SSL=0        # 1=SSL(465)，0=STARTTLS(587)
RELAY_SMTP_USERNAME=
RELAY_SMTP_PASSWORD=
```

- 此时 **DKIM 由你的邮件服务器签**（Postfix + OpenDKIM 等），本项目不做签名。
- 用户名留空 = 不走 AUTH，适用于允许匿名投递的内网中继。
- 很多云主机会封禁出站 25/465/587 —— 这正是本项目优先推荐 Resend（走 443）的原因。
  要用 SMTP 通道，先确认主机商没封端口。

---

## 11. 别照抄上游文档

网上（以及仓库里那份 [发邮件相关.md](发邮件相关.md)）流传的
`dreamhunter2333/cloudflare_temp_email` 配置说明**不适用于本项目**，例如：

| 上游有 | 本项目 |
|---|---|
| Cloudflare `send_email` binding 直发 | 没有。`deploy/cloudflare/` 只有入站 Worker，`wrangler.toml` 里没有该 binding |
| `verifiedAddressList` 兼容模式、4 层发信优先级 | 没有。只有"账户 SMTP"与"项目级中继"两条通道 |
| `RESEND_TOKEN` / `RESEND_TOKEN_<DOMAIN>` | 只有单一 `RESEND_API_KEY`，多域名靠同一个 Key |
| 多域名各自一个 Key | 一处 Key 覆盖所有 `INBOUND_DOMAINS` |

变量名、优先级规则都对不上，照抄会得到"配了却没生效"的结果。
以本仓库代码为准：[OutboundRelayServiceImpl.java](backend/src/main/java/com/mailsystem/service/impl/OutboundRelayServiceImpl.java)。

---

## 参考资料

- [Add and verify a domain — Resend](https://resend.com/docs/add-a-domain)
- [Cloudflare DNS 配置（Resend）](https://resend.com/docs/knowledge-base/cloudflare)
- [域名验证不通过怎么办](https://resend.com/docs/knowledge-base/what-if-my-domain-is-not-verifying)
- [账号配额与限制](https://resend.com/docs/knowledge-base/account-quotas-and-limits)
- [API 频率限制](https://resend.com/docs/api-reference/rate-limit)
