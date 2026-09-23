# 邮件系统 API 接口文档

> 2026 软件开发综合实训 — 邮件系统  
> Base URL: `http://localhost:8080`  
> Content-Type: `application/json` (除文件上传外)  
> 认证方式: `Authorization: Bearer <token>`

---

## 认证说明

除 **注册** 和 **登录** 外的所有接口，都需要在 HTTP Header 中携带 JWT Token：

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

Token 有效期为 **24 小时**，过期后需要重新登录。

---

## 1. 用户注册

```
POST /user/register
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| email | String | 是 | 邮箱地址 |
| password | String | 是 | 密码，6-32位 |
| nickname | String | 否 | 昵称 |

**请求示例:**

```json
{
  "email": "zhangsan@example.com",
  "password": "123456",
  "nickname": "张三"
}
```

**成功响应:**

```json
{
  "code": 200,
  "message": "注册成功",
  "data": {
    "id": 1,
    "email": "zhangsan@example.com",
    "nickname": "张三",
    "createTime": "2026-05-30 10:00:00"
  }
}
```

---

## 2. 用户登录

```
POST /user/login
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| email | String | 是 | 邮箱地址 |
| password | String | 是 | 密码 |

**请求示例:**

```json
{
  "email": "zhangsan@example.com",
  "password": "123456"
}
```

**成功响应:**

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "userId": 1,
    "email": "zhangsan@example.com",
    "nickname": "张三",
    "token": "eyJhbGciOiJIUzI1NiJ9..."
  }
}
```

---

## 3. 发送邮件

```
POST /mail/send
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| receiverIds | String | 是 | 收件人邮箱，多个用逗号分隔 |
| ccIds | String | 否 | 抄送人邮箱，多个用逗号分隔 |
| subject | String | 是 | 邮件主题 |
| body | String | 是 | 邮件正文（支持HTML） |
| attachmentIds | Long[] | 否 | 已上传的附件ID列表 |

**请求示例:**

```json
{
  "receiverIds": "lisi@example.com",
  "ccIds": "wangwu@example.com",
  "subject": "会议通知",
  "body": "<p>明天上午10点开会</p>",
  "attachmentIds": [1, 2]
}
```

**成功响应:**

```json
{
  "code": 200,
  "message": "发送成功",
  "data": {
    "id": 100,
    "senderEmail": "zhangsan@example.com",
    "receiverIds": "lisi@example.com",
    "subject": "会议通知",
    "body": "<p>明天上午10点开会</p>",
    "sendTime": "2026-05-30 10:30:00",
    "status": 1,
    "priority": 60,
    "isSpam": 0,
    "summary": "明天上午10点开会…",
    "category": "工作"
  }
}
```

---

## 4. 拉取收件箱

```
GET /mail/receive
```

无参数，自动识别当前登录用户。

**成功响应:**

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 100,
      "senderEmail": "zhangsan@example.com",
      "subject": "会议通知",
      "body": "<p>明天上午10点开会</p>",
      "sendTime": "2026-05-30 10:30:00",
      "priority": 60,
      "isSpam": 0,
      "category": "工作"
    }
  ]
}
```

---

## 5. 邮件列表（按类型）

```
GET /mail/list?type=1
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | Integer | 否 | 1=收件箱(默认), 2=已发送, 3=垃圾箱, 4=草稿 |

响应格式同 `/mail/receive`。

---

## 6. 邮件详情

```
GET /mail/detail/{id}
```

访问详情时自动标记已读。

**成功响应:**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 100,
    "senderEmail": "zhangsan@example.com",
    "receiverIds": "lisi@example.com",
    "ccIds": "wangwu@example.com",
    "subject": "会议通知",
    "body": "<p>明天上午10点开会</p>",
    "sendTime": "2026-05-30 10:30:00",
    "priority": 60,
    "isSpam": 0,
    "summary": "明天上午10点开会…",
    "category": "工作"
  }
}
```

---

## 6.1 原始报文（RFC822）

```
GET /mail/detail/{id}/raw
```

返回这封邮件重建后的完整 MIME 报文（`Content-Type: message/rfc822`），
正文与附件都在其中。供 SMTP/IMAP 协议代理使用 —— 邮件客户端抓信时需要整封报文。

> 与 `/mail/detail/{id}` 的区别：详情接口会**自动标记已读**，这个不会。
> 客户端同步时会批量抓取报文，若在这里标记已读，整个邮箱会在用户打开任何一封
> 邮件之前就变成已读。

---

## 7. 标记已读

```
PUT /mail/read/{id}
```

**成功响应:**

```json
{
  "code": 200,
  "message": "已标记为已读",
  "data": null
}
```

---

## 8. 删除邮件

```
DELETE /mail/delete/{id}
```

软删除，收件人侧移至垃圾箱。

**成功响应:**

```json
{
  "code": 200,
  "message": "已删除",
  "data": null
}
```

---

## 9. 搜索邮件

```
GET /mail/search?keyword=会议
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| keyword | String | 是 | 搜索关键词（匹配主题和正文） |

---

## 10. 上传附件

```
POST /attachment/upload
Content-Type: multipart/form-data
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | File | 是 | 文件，最大50MB |

**成功响应:**

```json
{
  "code": 200,
  "message": "上传成功",
  "data": {
    "id": 1,
    "fileName": "会议纪要.pdf",
    "fileSize": 204800,
    "contentType": "application/pdf",
    "uploadTime": "2026-05-30 10:25:00"
  }
}
```

---

## 11. 下载附件

```
GET /attachment/download/{id}
```

返回文件流，浏览器自动触发下载。

---

## 12. 转发邮件

```
POST /mail/forward/{id}
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| receiverEmails | String | 是 | 收件人邮箱，多个用逗号分隔 |
| ccEmails | String | 否 | 抄送人邮箱，多个用逗号分隔 |
| additionalBody | String | 否 | 附加说明文字 |

原邮件的主题会自动添加 `Fw:` 前缀，附件会自动复制。

---

## 13. 批量删除邮件

```
DELETE /mail/batch-delete
```

请求体: `[mailId1, mailId2, ...]`

```
DELETE /mail/batch-permanent-delete
```

请求体: `[mailId1, mailId2, ...]`（垃圾箱中使用，不可恢复）

---

## 14. 邮件增量同步

```
GET /mail/sync?since=2026-01-01 00:00:00
```

返回自指定时间以来的状态变更事件列表：

```json
{
  "code": 200,
  "data": [
    { "mailId": 100, "eventType": "NEW", "eventTime": "2026-01-01 10:30:00" },
    { "mailId": 101, "eventType": "READ", "eventTime": "2026-01-01 11:00:00" }
  ]
}
```

---

## 15. WebSocket 实时推送

连接端点: `ws://localhost:8080/ws` (SockJS fallback 可用)

客户端订阅频道: `/topic/user/{userId}`

推送消息格式:
```json
{
  "type": "NEW_MAIL",
  "payload": {
    "mailId": 100,
    "senderEmail": "sender@example.com",
    "subject": "邮件主题"
  },
  "timestamp": 1620000000000
}
```

---

## 16. 插件管理

**获取插件列表:**

```
GET /plugin/list
```

**响应:**

```json
{
  "code": 200,
  "data": [
    { "id": 1, "pluginName": "spamFilter", "enabled": 1, "description": "垃圾邮件识别插件" },
    { "id": 2, "pluginName": "prioritySort", "enabled": 1, "description": "邮件优先级排序插件" },
    { "id": 3, "pluginName": "linkDetection", "enabled": 1, "description": "恶意链接检测插件" },
    { "id": 4, "pluginName": "summaryGenerator", "enabled": 1, "description": "智能摘要生成插件" },
    { "id": 5, "pluginName": "categoryClassifier", "enabled": 1, "description": "智能分类插件" }
  ]
}
```

**启用/禁用插件:**

```
PUT /plugin/enable
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| pluginName | String | 是 | 插件名称 |
| enabled | Boolean | 是 | true=启用, false=禁用 |

---

## 17. LLM大模型配置

**获取LLM配置:**

```
GET /plugin/llm/config
```

**更新LLM配置:**

```
PUT /plugin/llm/configure
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| apiEndpoint | String | 否 | API端点 |
| apiKey | String | 否 | API密钥 |
| modelName | String | 否 | 模型名称 |
| enabled | Boolean | 否 | 是否启用 |

---

## 18. 邮箱账户

「邮箱账户」页的全部接口。账户有**两种类型**，由响应里的 `cloudflareRouting` 区分
（**不要**用 `smtpHost` 是否为空来判断）：

| `cloudflareRouting` | 类型 | 含义 |
|---|---|---|
| `false` | 绑定的外部邮箱 | 有 SMTP/IMAP 服务器与授权码，需要「测试连接」与定时收信 |
| `true` | 本域地址 | 没有服务器也没有授权码，收信由 Cloudflare 推送，发信走项目级中继 |

### 18.1 我的账户列表

```
GET /mail-account/list
```

**响应 `data` 元素字段：**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 账户 ID |
| emailAddress | String | 邮箱地址（本域地址即领到的那个） |
| displayName | String | 显示名，收件人看到的发件人名称 |
| providerType | String | `IMAP_SMTP` / `CLOUDFLARE` |
| cloudflareRouting | Boolean | 是否为本域地址，前端据此切换卡片形态 |
| smtpHost / smtpPort / smtpSsl / smtpUsername | | 发信服务器（本域地址为 null） |
| hasSmtpPassword / hasImapPassword | Boolean | **授权码是否已保存，不回传明文** |
| imapHost / imapPort / imapSsl / imapUsername | | 收信服务器（本域地址为 null） |
| enabled | Integer | 1=启用, 0=停用 |
| lastSyncTime / lastSyncStatus / lastSyncError | | 最近一次收信的时间、状态与失败原因 |
| syncedCount | Integer | 本次新增邮件数（仅 `/sync` 响应里有值） |

### 18.2 识别邮箱服务商

```
GET /mail-account/detect?email=xxx@qq.com&mx=false
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| email | String | 是 | 邮箱地址 |
| mx | Boolean | 否 | 是否允许 MX 记录反查（要做 DNS 查询，最长数秒），默认 false |

返回识别结果（`recognized` / `providerName` / 候选服务器列表 `smtpCandidates`
与 `imapCandidates` / 授权码获取指引 `guideUrl` 与 `guideSteps` 等）。
**只需邮箱地址，不需要授权码** —— 用于用户边打字边看到提示。

### 18.3 一键绑定（需要授权码）

```
POST /mail-account/quick-bind
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| emailAddress | String | 是 | 邮箱地址 |
| password | String | 是 | 授权码，不是登录密码 |
| displayName | String | 否 | 显示名 |
| receiveEnabled | Boolean | 否 | 是否同时收信（关闭则只用它对外发信） |

服务端会自动识别服务器地址并**真实连接探测**，只有连得通的一侧才会落库。

**响应 `data`：**

```json
{
  "account": { "id": 3, "emailAddress": "me@qq.com", "cloudflareRouting": false },
  "smtpOk": true,
  "smtpMessage": "连接成功",
  "imapOk": true,
  "imapMessage": "连接成功",
  "notices": []
}
```

> `notices` 非空时是「绑定成功了，但有几句话要说」（如授权码里含空格、
> 仅一侧连通）。**失败时的 `message` 是多行操作指引，需以弹窗完整展示。**

### 18.4 本域邮箱的能力说明

```
GET /mail-account/capabilities
```

| 字段 | 类型 | 说明 |
|------|------|------|
| inboundEnabled | Boolean | 本实例是否开启了入站接收。为 false 时整个「领取本域地址」入口不该显示 |
| domains | String[] | 可领取的域名列表 |
| outboundTransport | String | 发信中继的通道名：`resend` / `smtp` / `none` |
| outboundAvailable | Boolean | 发信中继是否就绪。为 false 时本域地址只能收信 |
| message | String | 给用户看的一句话说明，由后端生成 |

> **为什么收与发要分开说明**：它们是两条独立配置的链路（Cloudflare 负责收，
> 中继负责发）。只配了前者时，本域地址是「能收不能发」的 —— 这是一个合理且常见的
> 状态，不该被笼统地报成「功能不可用」。

### 18.5 领取本域地址（无需授权码）

```
POST /mail-account/inbound
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| address | String | 是 | 完整地址（`alice@mail.example.com`）或只填用户名（`alice`，服务端补默认域名） |
| displayName | String | 否 | 显示名 |

**没有授权码字段。** 这条路径不登录任何外部邮箱，因此不存在授权码 —— 详见
[Cloudflare.md](../Cloudflare.md)。

用户名只允许小写字母、数字与 `. _ + -`，不能以符号开头或结尾。
地址已被占用时返回明确报错。

**响应 `data`：** 同 18.1 的元素结构，`cloudflareRouting` 为 `true`。

### 18.6 手动配置 / 修改账户

```
POST /mail-account           # 手动配置新账户（需自行填写服务器地址）
PUT  /mail-account/{id}      # 修改
```

请求体同 18.1 的字段（授权码字段为**明文入参**，落库前 AES 加密）。
两个授权码字段**留空或回传掩码表示不修改**。修改本域地址时只需提交
`emailAddress` + `displayName` + `enabled` 三项。

> 改了邮箱地址或 IMAP 服务器/登录名会重置收信水位线（那是另一个信箱了，
> UID 空间完全不同）；只换授权码不会。

### 18.7 测试连接 / 立即收信 / 解绑

```
POST   /mail-account/{id}/test      # 分别测 SMTP 与 IMAP，任一侧失败不影响另一侧
POST   /mail-account/{id}/sync      # 阻塞到本轮结束，响应里带 syncedCount
DELETE /mail-account/{id}           # 解绑
```

> 这三个接口只对 `cloudflareRouting = false` 的账户有意义 —— 本域地址没有服务器
> 可测，收信也不是轮询而是被推送，前端会隐藏这些按钮。

---

## 19. 入站接收（Cloudflare Worker 回调）

```
POST /inbound/cloudflare
```

**这是给 Cloudflare Email Worker 调用的接口，不是给前端调用的。**
它需要 HMAC-SHA256 签名而不是 JWT：

| 请求头 | 说明 |
|--------|------|
| `X-Inbound-Timestamp` | Unix 秒级时间戳，超出 5 分钟窗口即拒绝 |
| `X-Inbound-Signature` | `v1=` + `HMAC-SHA256(共享密钥, timestamp + "." + 请求体原始字节)` 的十六进制小写 |

请求体为 `{from, to, receivedAt, raw}`（`raw` 是 base64 编码的原始报文）。

**响应**：业务结果一律用 HTTP 200 + `data.status` 表达，让 Worker 能读到具体
原因并据此区分「该退信」还是「该重试」：

| `data.status` | 含义 | Worker 的动作 |
|---|---|---|
| `DELIVERED` | 已入库，`data.mailId` 是站内邮件 ID | 视为成功 |
| `DUPLICATE` | 这封信已存在（按 `Message-ID` 去重），未重复入库 | 视为成功 |
| `UNKNOWN_RECIPIENT` | 没有这个收件地址 | **退信**给发件人 |
| `DISABLED` | 收件地址已停用 | **退信**给发件人 |

HTTP 层面的状态码语义不同 —— 它们表达的是「这次调用本身出了什么问题」：
签名不一致 401、请求体畸形或 `raw` 非 Base64 400、超过大小上限 413、
未启用入站接收或未配密钥 503、处理失败（暂时性故障）500。

> 500 与 `UNKNOWN_RECIPIENT` 的区别是要害：前者 Worker 抛出异常让 Cloudflare
> 稍后重试，后者立刻退信。若把「地址不存在」也做成 4xx，Worker 只会看到
> 「失败了」，无法区分该退信还是该重试 —— 那就会变成一封谁也不知道下落的信。

部署步骤见 [deploy/cloudflare/README.md](../deploy/cloudflare/README.md)。

---

## 分页说明

邮件列表接口 (`/mail/list`, `/mail/search`) 支持分页参数:

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| page | int | 1 | 页码 |
| pageSize | int | 20 | 每页大小 |

分页响应格式:
```json
{
  "code": 200,
  "data": {
    "records": [...],
    "total": 150,
    "page": 1,
    "pageSize": 20
  }
}
```

---

## 错误响应格式

```json
{
  "code": 401,
  "message": "Token无效或已过期",
  "data": null
}
```

| 状态码 | 说明 |
|--------|------|
| 200 | 成功 |
| 401 | 未登录或Token过期 |
| 500 | 服务器内部错误 / 业务错误 |

---

## 智能插件说明

| 插件名称 | 功能 | 执行方式 |
|----------|------|----------|
| spamFilter | 垃圾邮件关键词+规则识别 | 同步 |
| prioritySort | 基于内容的多维度优先级评分 | 同步 |
| linkDetection | 恶意URL+伪造发件人检测 | 同步 |
| summaryGenerator | 正文摘要自动提取 | 异步 |
| categoryClassifier | 关键词自动分类 | 异步 |
