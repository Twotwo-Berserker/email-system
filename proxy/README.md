# SMTP / IMAP 协议代理

让 **Thunderbird、Outlook、手机自带邮件 App** 这类传统邮件客户端
直接访问本系统的邮箱。

---

## 它是什么

一层协议转换：

```
邮件客户端  ──SMTP/IMAP──▶  本代理  ──HTTP + JWT──▶  邮件系统后端
```

代理**没有数据库、没有自己的用户体系、不持有任何邮箱凭据**。
用户登录代理时用的是本系统自己的账号密码（换取 JWT），
权限判断仍然全部由后端完成。

这正是不需要外部邮箱授权码的原因：代理不是"另一个能访问你邮箱的东西"，
它只是 Web 界面的另一种呈现形式。

---

## 快速开始

### 本地直接运行

```bash
cd proxy
python server.py                        # 默认连 localhost:8080
python server.py --backend http://localhost:8080 --verbose
```

### 跟着整套系统一起跑（推荐）

仓库根目录的 `docker-compose.yml` 里已经包含这个服务，`docker-compose up -d`
之后它就在 `proxy:143` / `proxy:2525` 上待着了。

---

## 客户端怎么配

| 配置项 | 值 |
|---|---|
| 用户名 | **你的本系统账号邮箱**（登录 Web 端用的那个） |
| 密码 | **你的本系统账号密码** |
| IMAP 服务器 | 代理所在主机 |
| IMAP 端口 | `143`，加密方式选 **无 / STARTTLS 关闭** |
| SMTP 服务器 | 代理所在主机 |
| SMTP 端口 | `2525` |
| SMTP 认证 | **需要**，选 `PLAIN` 或 `LOGIN` |

> ⚠️ 多数客户端会默认勾选 SSL/TLS。代理本身不做 TLS（见下方「安全」），
> 因此必须手动把加密设为"无"，否则客户端连不上。
> 端口填 143 / 2525 而不是 993 / 465 也是这个原因 —— 后两个端口隐含 SSL。

### 邮箱名称对应关系

| 客户端里看到的 | 后端 |
|---|---|
| `INBOX` | 收件箱 |
| `Sent` | 已发送（`Sent Items`、`已发送` 等同义名都认） |
| `Drafts` | 草稿箱 |
| `Trash` | 垃圾箱（`Deleted Items`、`已删除` 等同义名都认） |

---

## 已支持的能力

**IMAP**：`LOGIN` / `AUTHENTICATE PLAIN` / `CAPABILITY` / `ID` / `NAMESPACE` /
`LIST` / `LSUB` / `STATUS` / `SELECT` / `EXAMINE` / `FETCH` / `UID FETCH` /
`SEARCH` / `UID SEARCH` / `STORE` / `UID STORE` / `EXPUNGE` / `CLOSE` /
`UNSELECT` / `NOOP` / `LOGOUT`

`FETCH` 支持 `UID`、`FLAGS`、`RFC822.SIZE`、`INTERNALDATE`、`ENVELOPE`、
`BODYSTRUCTURE`，以及 `BODY[]`、`BODY[HEADER]`、`BODY[TEXT]`、
`BODY[HEADER.FIELDS (...)]` 和对应的 `.PEEK` 形式。

**SMTP**：`EHLO` / `HELO` / `AUTH PLAIN` / `AUTH LOGIN` / `MAIL` / `RCPT` /
`DATA` / `RSET` / `NOOP` / `VRFY` / `QUIT`

---

## 已知的限制

这些都是**有意的取舍**，不是没做完：

| 限制 | 原因 |
|---|---|
| 不加密（无 SSL/TLS） | 代理定位是内网组件。生产环境请在它前面放一个 TLS 终结（Nginx `stream` 模块 / stunnel），而不是让代理自己管证书 |
| 不支持 `APPEND` | 本系统没有"从客户端保存邮件"的接口。发送的邮件会自动出现在「已发送」里，因此影响很小；客户端会提示"无法保存到已发送" |
| 附件较大的信可能较慢 | 附件要先经代理上传到后端，再随发信请求提交，比 Web 端多一次往返 |
| 正文只发纯文本 | 后端不提供"保存到已发送"以外的富文本接口；纯文本在各客户端上表现最一致 |
| 单次 `SELECT` 最多载入 500 封 | 防止一个攒了几万封的邮箱把代理内存吃满。客户端本来就只懒加载可见部分 |
| `BODY[1]` 这类带段落号的请求返回整封报文 | 后端只有"整封报文"这一种粒度。客户端拿到完整报文后能自己定位段落，而返回错内容会让它显示乱码 |
| 复杂 `SEARCH` 条件会被忽略 | 只精确支持 `ALL` / `UNSEEN` / `SEEN` / `FROM` / `SUBJECT` / `TO` / `UID`。认不出的条件按"都匹配"处理 —— 多返回几封比让客户端报"搜索失败"好 |

---

## 排查

开 `--verbose`（或 `PROXY_VERBOSE=true`）会打印每一个协议命令，
客户端兼容性问题基本看几行日志就能定位。

| 现象 | 原因与处置 |
|---|---|
| 客户端提示"无法连接" | 多半是它默认开了 SSL。把加密方式改成"无"，端口改成 143 / 2525 |
| 登录失败 | 用的是**本系统**的账号密码，不是外部邮箱的授权码。Web 端能登录的账号就一定能登录这里 |
| 收件箱是空的 | 先确认 Web 端能看到邮件；再看 `--verbose` 日志里 `SELECT` 返回的 `EXISTS` 数 |
| 中文主题显示成 `=?UTF-8?B?...?=` | 客户端的编码处理有问题。ENVELOPE 按 RFC 3501 返回原始编码词，正常客户端会自己解码 |
| 时间显示成 1970 年 | `INTERNALDATE` 解析失败。查看日志里 `INTERNALDATE` 那一项的实际输出 |
| 发信失败 | 日志里会带上后端返回的原因（如"收件人不存在"），它直接来自后端 |

---

## 代码结构

| 文件 | 职责 |
|---|---|
| `server.py` | 两个协议服务器：IMAP 命令循环、SMTP 状态机，以及协议的解析工具 |
| `backend.py` | 后端 REST 接口的封装。所有网络调用都收在这里 |
| `mime.py` | RFC822 报文解析 —— 为 IMAP 的 `ENVELOPE` / `BODYSTRUCTURE` 提供数据 |

**零第三方依赖**，只用 Python 标准库。部署者不需要 `pip install`
任何东西 —— 一个"为了收信还得先配 Python 环境"的代理，实际会劝退大部分人。
标准库里的 `smtpd` / `asyncore` 在 Python 3.12 已被移除，
因此 SMTP 的状态机是自己写的（这反而更可控）。
