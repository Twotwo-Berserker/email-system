"""RFC822 报文解析 —— 为 IMAP 的 ENVELOPE / BODYSTRUCTURE 提供数据。

IMAP 客户端在列表页要的是**结构化**的信头（谁发的、主题、时间），
在正文页要的是**原始字节**。后端交给我们的是重建好的完整报文，
这里负责把它拆开。
"""

import re
from datetime import datetime, timedelta, timezone
from email import message_from_bytes
from email.utils import getaddresses, parsedate_to_datetime

# 报文头与正文之间的分隔。JavaMail 输出的是 CRLF，但外部来信被重建时
# 也可能出现裸 LF，因此两种都认
_BLANK_LINE = re.compile(rb"\r?\n\r?\n")


def parse(raw):
    """把字节解析成 email.message.Message。

    policy 用默认的 compat32：它保留原始头部字符串（不自动解码），
    而 IMAP 的 ENVELOPE 恰恰需要原始形态 —— 解码是客户端的事，
    代理替它决定编码方式会让部分客户端显示成乱码。
    """
    return message_from_bytes(raw)


def split_header_body(raw):
    """拆出报文的头部与正文两部分（含各自末尾的换行）。"""
    match = _BLANK_LINE.search(raw)
    if not match:
        # 没有空行：整封都是头（畸形报文），正文为空
        return raw, b""
    end = match.end()
    return raw[:end], raw[end:]


def pick_header_fields(raw, names):
    """按字段名过滤报文头，用于 BODY[HEADER.FIELDS (...)]。

    保留原顺序、原大小写，并在末尾补一个空行 —— RFC 3501 要求这段内容
    本身就是一个合法的头部块，缺了结尾空行客户端会一直等下一个字段。
    """
    wanted = {name.strip().lower() for name in names}
    header, _ = split_header_body(raw)

    kept = []
    for line in header.splitlines(keepends=True):
        if not line.strip():
            continue
        text = line.decode("latin-1", errors="replace")
        name = text.split(":", 1)[0].strip().lower() if ":" in text else ""
        if name in wanted:
            kept.append(line if line.endswith(b"\n") else line + b"\n")

    kept.append(b"\r\n")
    return b"".join(kept)


def _quote(value):
    """IMAP 的 quoted string。NIL 用 None 表示。"""
    if value is None:
        return "NIL"
    text = str(value)
    if not text:
        return '""'
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _address_list(header_value):
    """把信头里的地址串转成 IMAP 的地址列表结构。

    结构为 ((姓名 源路由 邮箱用户名 主机名) ...)。源路由（adl，形如
    @a,@b:）在现代邮件里已不使用，固定填 NIL。
    """
    if not header_value:
        return "NIL"
    addresses = getaddresses([header_value])
    if not addresses:
        return "NIL"

    items = []
    for name, address in addresses:
        if not address:
            continue
        if "@" in address:
            mailbox, host = address.rsplit("@", 1)
        else:
            mailbox, host = address, None
        items.append("(%s %s %s %s)" % (
            _quote(name or None),
            "NIL",
            _quote(mailbox),
            _quote(host),
        ))
    return "(" + " ".join(items) + ")" if items else "NIL"


def envelope(message):
    """构造 IMAP 的 ENVELOPE 结构。

    顺序是 RFC 3501 定的，不能改：
    (date subject from sender reply-to to cc bcc in-reply-to message-id)

    所有字段都用**信头里的原始形态**（含 `=?UTF-8?B?...?=` 这类编码词），
    不在这里解码。两个原因：其一，把中文解出来之后就是 8 位字节，
    而 IMAP 的 quoted string 语法只允许 7 位字符，严格实现的客户端会直接
    拒绝整个响应；其二，解码是客户端的职责，各家实现的处理方式
    （尤其是乱码时的兜底）比我们替代它决定要稳妥。
    """
    sender = message.get("Sender") or message.get("From")
    return "(%s %s %s %s %s %s %s %s %s %s)" % (
        _quote(message.get("Date")),
        _quote(message.get("Subject")),
        _address_list(message.get("From")),
        _address_list(sender),
        _address_list(message.get("Reply-To")),
        _address_list(message.get("To")),
        _address_list(message.get("Cc")),
        _address_list(message.get("Bcc")),
        _quote(message.get("In-Reply-To")),
        _quote(message.get("Message-ID")),
    )


def internal_date(message, fallback=None):
    """INTERNALDATE 字段值。

    取信头的 Date；解析不了就退化为后端记录的发送时间，再不行用当前时间。
    IMAP 要求这个字段必须存在，返回 NIL 会让部分客户端把时间显示成 1970 年。
    """
    value = message.get("Date")
    if value:
        try:
            moment = parsedate_to_datetime(value)
            if moment is not None:
                return format_internal_date(moment)
        except Exception:
            pass
    if fallback is not None:
        try:
            return format_internal_date(fallback)
        except Exception:
            pass
    return format_internal_date(datetime.now(timezone.utc))


# IMAP 的 INTERNALDATE 有自己的一套格式，不能用 email.utils.format_datetime ——
# 后者输出的是 RFC 2822 格式（带星期几、"+0000" 写成 "+0000"），
# 客户端按 IMAP 的语法解析会得到一个非法日期，然后把整封邮件的时间显示成 1970 年
_MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun",
           "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


def format_internal_date(moment):
    """把 datetime 转成 IMAP 的 INTERNALDATE 格式：`dd-Mmm-yyyy HH:mm:ss +ZZZZ`。"""
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=datetime.now().astimezone().tzinfo)
    offset = moment.utcoffset() or timedelta(0)
    total_minutes = int(offset.total_seconds() // 60)
    sign = "+" if total_minutes >= 0 else "-"
    total_minutes = abs(total_minutes)
    zone = "%s%02d%02d" % (sign, total_minutes // 60, total_minutes % 60)
    return "%02d-%s-%04d %02d:%02d:%02d %s" % (
        moment.day, _MONTHS[moment.month - 1], moment.year,
        moment.hour, moment.minute, moment.second, zone)


def bodystructure(message):
    """构造 IMAP 的 BODYSTRUCTURE。

    只填 RFC 3501 规定的必需字段，不带扩展字段（disposition、language 等）——
    它们都是可选的，而少填客户端只会"知道得少一点"，填错则会让整个响应被
    判为畸形，连邮件都打不开。
    """
    if message.is_multipart():
        parts = []
        for part in message.get_payload():
            parts.append(bodystructure(part))
        # 多段结构：(分段1 分段2 ... "子类型")
        subtype = message.get_content_subtype() or "mixed"
        if message.get_content_maintype() == "multipart" and subtype == "digest":
            # multipart/digest 的子段默认类型是 message/rfc822 而非 text/plain，
            # 这一点客户端会区别对待
            return "(" + "".join(parts) + " " + _quote(subtype) + ")"
        return "(" + "".join(parts) + " " + _quote(subtype) + ")"

    maintype = message.get_content_maintype() or "application"
    subtype = message.get_content_subtype() or "octet-stream"

    params = message.get_params() or []
    # get_params 的第一个元素是整体类型（如 'text/plain'），其余才是参数
    param_items = [(k, v) for k, v in params[1:] if k and v]
    param_str = "NIL"
    if param_items:
        param_str = "(" + " ".join(
            "%s %s" % (_quote(k.upper()), _quote(v)) for k, v in param_items) + ")"

    payload = message.get_payload(decode=True) or b""
    size = len(payload)

    fields = [
        _quote(maintype.upper()),
        _quote(subtype.upper()),
        param_str,
        _quote(message.get("Content-ID")),
        _quote(message.get("Content-Description")),
        _quote((message.get("Content-Transfer-Encoding") or "7BIT").upper()),
        str(size),
    ]
    if maintype == "text":
        # 文本段必须带行数，客户端据此算滚动条与预览
        lines = payload.count(b"\n")
        fields.append(str(lines))
    return "(" + " ".join(fields) + ")"
