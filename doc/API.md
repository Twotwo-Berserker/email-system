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
