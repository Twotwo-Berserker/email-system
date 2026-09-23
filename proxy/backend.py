"""邮件系统 REST API 客户端。

代理不直接连数据库，也不持有任何邮件协议凭据 —— 它把用户的每一次操作
翻译成对后端 REST 接口的调用。这样做的好处是权限判断只有一份实现
（在后端），代理不可能绕开它。

代理登录用的凭证是**本系统自己的账号密码**（换取 JWT），
不是 QQ 邮箱等外部邮箱的授权码。
"""

import json
import mimetypes
import urllib.error
import urllib.parse
import urllib.request
import uuid

# 邮箱类型编号，与后端 /mail/list?type= 的取值一一对应
BOX_INBOX = 1
BOX_SENT = 2
BOX_TRASH = 3
BOX_DRAFTS = 4


class BackendError(Exception):
    """后端返回了业务错误（HTTP 非 2xx，或响应体里 code != 200）。

    message 直接取自后端的 message 字段 —— 后端写的提示语是面向用户的，
    代理原样转给邮件客户端即可，不要在中间再包一层自己的措辞。
    """

    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


class Backend:
    """后端接口的薄封装。每个方法都要求显式传入 token。

    不把 token 存在实例上：一个 Backend 实例会被所有连接共享，
    而 token 是**每个连接各自登录**得来的。存成实例字段会让 A 用户的
    请求带上 B 用户的身份。
    """

    def __init__(self, base_url, timeout=30):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    # ==================== 认证 ====================

    def login(self, email, password):
        """用本系统的账号密码换取 JWT。

        :raises BackendError: 账号或密码错误
        """
        data = self._json_request("POST", "/user/login", body={
            "email": email,
            "password": password,
        })
        token = (data or {}).get("token")
        if not token:
            raise BackendError("登录响应里没有 token")
        return data

    # ==================== 邮件读取 ====================

    def list_mails(self, token, box_type, page=1, page_size=50):
        """按邮箱类型分页取邮件。

        :return: {"records": [...], "total": n}
        """
        query = urllib.parse.urlencode({
            "type": box_type,
            "page": page,
            "pageSize": page_size,
        })
        return self._json_request("GET", "/mail/list?" + query, token=token)

    def list_all_mails(self, token, box_type, max_items=500):
        """取某个邮箱下的全部邮件（用于 IMAP SELECT 时确定邮件序列）。

        没有"一次拿完"的接口，因此翻页累加。设上限是为了避免一个攒了几万封
        的收件箱把代理的内存吃满 —— 邮件客户端对超大邮箱本来也只会懒加载。
        """
        records = []
        page = 1
        page_size = 100
        while len(records) < max_items:
            result = self.list_mails(token, box_type, page, page_size)
            batch = (result or {}).get("records") or []
            records.extend(batch)
            total = (result or {}).get("total") or 0
            if not batch or len(records) >= total:
                break
            page += 1
        return records[:max_items]

    def get_mail(self, token, mail_id):
        return self._json_request("GET", "/mail/detail/%d" % mail_id, token=token)

    def get_raw(self, token, mail_id):
        """取重建好的 RFC822 报文（后端用库内字段拼出来的，见 MimeBuilder）。"""
        return self._request("GET", "/mail/detail/%d/raw" % mail_id, token=token)

    # ==================== 状态变更 ====================

    def set_read(self, token, mail_id, read):
        path = "/mail/read/%d" if read else "/mail/unread/%d"
        return self._json_request("PUT", path % mail_id, token=token)

    def delete_mail(self, token, mail_id):
        """移入垃圾箱（软删除），与 Web 端的删除行为一致。"""
        return self._json_request("DELETE", "/mail/delete/%d" % mail_id, token=token)

    def send(self, token, receivers, subject, body, cc=None, attachment_ids=None):
        """发信。receivers / cc 都是逗号分隔的字符串，与 Web 端一致。"""
        return self._json_request("POST", "/mail/send", token=token, body={
            "receiverEmails": receivers,
            "ccEmails": cc or "",
            "subject": subject,
            "body": body,
            "attachmentIds": attachment_ids or [],
        })

    def upload_attachment(self, token, filename, content_type, data):
        """上传一个附件，返回它的 ID。

        后端 /mail/send 只接受附件 ID（Web 端是先上传再发送），
        因此 SMTP 收到的附件必须先走这一步。
        """
        boundary = "----MailSystemProxy" + uuid.uuid4().hex
        body = _multipart_body(boundary, "file", filename, content_type, data)
        headers = {
            "Content-Type": "multipart/form-data; boundary=" + boundary,
        }
        result = self._json_request("POST", "/attachment/upload", token=token,
                                   body=body, raw_body=True, headers=headers)
        attachment_id = (result or {}).get("id")
        if attachment_id is None:
            raise BackendError("附件上传后没有返回 ID")
        return attachment_id

    # ==================== HTTP ====================

    def _json_request(self, method, path, token=None, body=None, raw_body=False,
                      headers=None):
        payload = self._request(method, path, token=token, body=body,
                               raw_body=raw_body, headers=headers)
        if not payload:
            return None
        try:
            wrapper = json.loads(payload.decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as e:
            raise BackendError("后端返回的不是 JSON: %s" % e)

        # 统一响应体 {code, message, data}。code != 200 也要按错误处理 ——
        # 后端的业务错误大多走这个通道，HTTP 状态码仍是 200
        code = wrapper.get("code")
        if code != 200:
            raise BackendError(wrapper.get("message") or "后端返回错误 (code=%s)" % code)
        return wrapper.get("data")

    def _request(self, method, path, token=None, body=None, raw_body=False,
                 headers=None):
        url = self.base_url + path
        request_headers = dict(headers or {})

        if token:
            request_headers["Authorization"] = "Bearer " + token
        request_headers.setdefault("Accept", "application/json")

        data = None
        if body is not None:
            if raw_body:
                data = body
            else:
                data = json.dumps(body).encode("utf-8")
                request_headers.setdefault("Content-Type", "application/json")

        request = urllib.request.Request(url, data=data, method=method,
                                        headers=request_headers)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return response.read()
        except urllib.error.HTTPError as e:
            raise BackendError(_http_error_message(e), status=e.code)
        except urllib.error.URLError as e:
            raise BackendError("无法连接后端 %s: %s" % (self.base_url, e.reason))


def _http_error_message(error):
    """从 HTTP 错误响应里取出可读信息。

    后端在 4xx/5xx 时同样返回 {code, message} 结构的 JSON，直接抛裸的
    "HTTP Error 401" 会让用户完全不知道发生了什么。
    """
    try:
        payload = error.read().decode("utf-8")
        wrapper = json.loads(payload)
        if isinstance(wrapper, dict):
            return wrapper.get("message") or payload[:200]
        return payload[:200]
    except Exception:
        return "HTTP %s" % error.code


def _multipart_body(boundary, field_name, filename, content_type, data):
    """手工拼一个 multipart/form-data 请求体。

    标准库没有现成的编码器，而为了这一个用途引入 requests 会让代理
    从"零依赖"变成"需要 pip install"—— 对部署者来说差别很大。
    """
    if not content_type:
        content_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
    # 文件名可能含非 ASCII 字符，按 RFC 2231 编码，否则后端解析出来的文件名是乱码
    encoded_name = urllib.parse.quote(filename, safe="")
    crlf = b"\r\n"
    parts = [
        b"--" + boundary.encode("ascii"),
        ('Content-Disposition: form-data; name="%s"; filename*=UTF-8\'\'%s'
         % (field_name, encoded_name)).encode("utf-8"),
        ("Content-Type: " + content_type).encode("utf-8"),
        b"",
        data,
        b"--" + boundary.encode("ascii") + b"--",
        b"",
    ]
    return crlf.join(parts)
