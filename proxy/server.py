"""SMTP / IMAP 协议代理 —— 让传统邮件客户端能访问本系统的邮箱。

============================================================
它解决什么问题
============================================================

本系统的邮箱（无论用户自己绑定的外部邮箱，还是领取的本域地址）都是通过
Web 界面访问的。但很多场景下用户想用自己习惯的邮件客户端：Thunderbird、
Outlook、手机自带的邮件 App。这些客户端只会说 SMTP 和 IMAP。

这个代理就是那层协议转换：它对外说 SMTP/IMAP，对内调用本系统的 REST 接口。

============================================================
为什么这不违反"绕过授权码"的前提
============================================================

代理**不持任何外部邮箱的凭据**，也没有自己的用户库。用户登录代理时用的是
本系统自己的账号密码（换取 JWT），代理拿着这个 JWT 去调后端 —— 权限判断
仍然只有后端那一份实现。

换句话说：代理不是"另一个能访问你邮箱的东西"，它只是本项目 Web 界面的
另一种呈现形式。

============================================================
零依赖
============================================================

只用 Python 标准库。部署者不需要 pip install 任何东西 ——
一个"为了收信还要先配 Python 环境"的代理，实际会劝退大部分人。
标准库里的 smtpd/asyncore 在 3.12 已被移除，因此 SMTP 的状态机是自己写的。
"""

import argparse
import base64
import email
import logging
import os
import re
import socketserver
import sys
import threading
from datetime import datetime
from email.header import decode_header, make_header

import mime
from backend import (BOX_DRAFTS, BOX_INBOX, BOX_SENT, BOX_TRASH, Backend,
                     BackendError)

LOG = logging.getLogger("proxy")

# ============================================================
# 邮箱名 ↔ 后端类型编号
# ============================================================

# IMAP 的邮箱是扁平命名空间里的字符串，没有"这是收件箱"这种语义 ——
# INBOX 是唯一被 RFC 3501 规定必须存在的名字，其余名字各家客户端各叫各的。
# 因此这里把常见的别名一并认下，否则 Thunderbird 打开 "Sent Items" 会得到
# 一个空邮箱（它不会报错，只是显示没有邮件 —— 这种问题最难被发现）。
MAILBOX_ALIASES = {
    "inbox": (BOX_INBOX, "INBOX"),
    "sent": (BOX_SENT, "Sent"),
    "sent items": (BOX_SENT, "Sent"),
    "sent messages": (BOX_SENT, "Sent"),
    "sent-mail": (BOX_SENT, "Sent"),
    "已发送": (BOX_SENT, "Sent"),
    "trash": (BOX_TRASH, "Trash"),
    "deleted items": (BOX_TRASH, "Trash"),
    "deleted messages": (BOX_TRASH, "Trash"),
    "已删除": (BOX_TRASH, "Trash"),
    "drafts": (BOX_DRAFTS, "Drafts"),
    "草稿箱": (BOX_DRAFTS, "Drafts"),
}

# LIST 里对客户端公布的邮箱，顺序固定 —— 客户端一般按返回顺序展示
MAILBOX_LIST = ["INBOX", "Sent", "Drafts", "Trash"]

# 一次 SELECT 最多载入多少封。与后端 list_all_mails 的上限对齐
MAX_MESSAGES = 500

# IMAP 的 UID 直接复用后端的 mail.id。它是自增主键，永不复用，
# 正好满足 RFC 3501 对 UID 的两条要求：稳定、严格递增。
UIDVALIDITY_BASE = 1000


# ============================================================
# IMAP
# ============================================================

class ImapSession:
    """一个 IMAP 连接的全部状态。

    每个连接各自持有：登录后的 JWT、当前选中的邮箱、邮件序列。
    不跨连接共享任何东西 —— 共享会让 A 用户看到 B 用户的邮件。
    """

    def __init__(self, backend, reader, writer):
        self.backend = backend
        self.reader = reader
        self.writer = writer

        self.token = None
        self.user_email = None
        self.user_id = None

        self.mailbox = None
        self.box_type = None
        self.messages = []          # 后端返回的邮件 dict 列表，下标即序列号-1

        # 以下三项都是"代理侧的会话状态"，不落库：
        # \Deleted 只有在 EXPUNGE 时才真正通知后端删除，
        # 这是 IMAP 的语义（用户可能又撤销了删除标记）
        self.deleted = set()        # 被标记 \Deleted 的 UID
        self.flagged = set()        # 被标记 \Flagged 的 UID
        self.seen_override = {}     # UID → 本地覆盖的已读状态

        self.raw_cache = {}         # UID → 原始报文（一封邮件在一次会话里只取一次）

    # ---------- 登录态 ----------

    @property
    def authenticated(self):
        return self.token is not None

    # ---------- 序列与 UID ----------

    def uid_of(self, index):
        """后端邮件 ID 即 UID。"""
        return int(self.messages[index].get("id"))

    def index_of_uid(self, uid):
        for i, message in enumerate(self.messages):
            if int(message.get("id")) == uid:
                return i
        return None

    def is_seen(self, index):
        uid = self.uid_of(index)
        if uid in self.seen_override:
            return self.seen_override[uid]
        return bool(self.messages[index].get("isRead"))

    def flags_of(self, index):
        flags = []
        if self.is_seen(index):
            flags.append("\\Seen")
        if self.uid_of(index) in self.deleted:
            flags.append("\\Deleted")
        if self.uid_of(index) in self.flagged:
            flags.append("\\Flagged")
        return "(" + " ".join(flags) + ")"

    def raw_of(self, index):
        uid = self.uid_of(index)
        if uid not in self.raw_cache:
            self.raw_cache[uid] = self.backend.get_raw(self.token, uid)
        return self.raw_cache[uid]

    # ---------- 输出 ----------

    def send_line(self, text):
        self.writer.write(text.encode("utf-8", errors="replace") + b"\r\n")

    def send_bytes(self, data):
        self.writer.write(data)

    def flush(self):
        self.writer.flush()


class ImapHandler(socketserver.StreamRequestHandler):
    """IMAP4rev1 的命令循环。

    实现的命令见 _dispatch 的分支。刻意不实现的能力（STARTTLS、ACL、
    QUOTA 等）在 CAPABILITY 里也不声明 —— 声明了却不支持，客户端会尝试
    使用然后失败，比一开始就不声明糟糕得多。
    """

    def handle(self):
        session = ImapSession(self.server.backend, self.rfile, self.wfile)
        self.session = session
        LOG.info("IMAP 连接来自 %s", self.client_address)

        session.send_line("* OK [CAPABILITY IMAP4rev1 AUTH=PLAIN] MailSystem proxy ready")
        session.flush()

        while True:
            try:
                line = self.rfile.readline()
            except (ConnectionError, OSError):
                break
            if not line:
                break
            line = line.rstrip(b"\r\n")
            if not line:
                continue

            try:
                self._dispatch(line)
            except (ConnectionError, OSError):
                break
            except Exception as e:  # noqa: BLE001 — 一个命令出错不该断开连接
                LOG.exception("IMAP 命令处理异常")
                session.send_line("* BAD 内部错误: %s" % _one_line(str(e)))
            session.flush()

        LOG.info("IMAP 连接结束 %s", self.client_address)

    # ---------- 命令分发 ----------

    def _dispatch(self, line):
        session = self.session
        parts = _tokenize(line)
        if len(parts) < 2:
            session.send_line("* BAD 命令格式错误")
            return

        # _tokenize 返回的是 bytes：命令名要转成 str 才能用来找处理方法，
        # 而参数保持 bytes —— 它们可能是二进制（APPEND 的字面量）
        tag = _text(parts[0])
        command = _text(parts[1]).upper()
        args = parts[2:]

        handler = getattr(self, "_cmd_" + command.lower(), None)
        if handler is None:
            session.send_line("%s BAD 不支持的命令 %s" % (tag, command))
            return
        handler(tag, args)

    def _ok(self, tag, text="完成"):
        self.session.send_line("%s OK %s" % (tag, text))

    def _no(self, tag, text):
        self.session.send_line("%s NO %s" % (tag, text))

    def _require_auth(self, tag):
        if not self.session.authenticated:
            self._no(tag, "请先登录")
            return False
        return True

    def _require_selected(self, tag):
        if self.session.box_type is None:
            self._no(tag, "尚未选中邮箱")
            return False
        return True

    # ---------- 连接管理 ----------

    def _cmd_capability(self, tag, args):
        # 不声明 STARTTLS：代理跑在内网/本机，客户端若协商 STARTTLS 会失败。
        # 生产部署应在代理前面套一层 TLS 终结（见 README）
        self.session.send_line("* CAPABILITY IMAP4rev1 AUTH=PLAIN ID NAMESPACE")
        self._ok(tag)

    def _cmd_noop(self, tag, args):
        self._ok(tag)

    def _cmd_logout(self, tag, args):
        self.session.send_line("* BYE 再见")
        self._ok(tag)
        raise ConnectionError("client logged out")

    def _cmd_id(self, tag, args):
        # RFC 2971。Thunderbird 登录后必发，不回会让它把连接判为异常
        self.session.send_line('* ID ("name" "MailSystem Proxy" "version" "1.0")')
        self._ok(tag)

    def _cmd_namespace(self, tag, args):
        # 只有一个扁平命名空间，分隔符用 "/"
        self.session.send_line('* NAMESPACE (("" "/")) NIL NIL')
        self._ok(tag)

    # ---------- 认证 ----------

    def _cmd_login(self, tag, args):
        if len(args) < 2:
            self._no(tag, "LOGIN 需要用户名与密码")
            return
        email = _text(args[0])
        password = _text(args[1])
        self._authenticate(tag, email, password)

    def _cmd_authenticate(self, tag, args):
        if not args or _text(args[0]).upper() != "PLAIN":
            self._no(tag, "只支持 AUTHENTICATE PLAIN")
            return
        # 服务端发一个续行提示，客户端把 base64(\\0user\\0pass) 发回来
        self.session.send_line("+ ")
        self.session.flush()
        raw = self.rfile.readline().rstrip(b"\r\n")
        try:
            decoded = base64.b64decode(raw).decode("utf-8")
        except Exception:
            self._no(tag, "认证数据不是合法的 Base64")
            return
        pieces = decoded.split("\0")
        if len(pieces) < 3:
            self._no(tag, "认证数据格式错误")
            return
        self._authenticate(tag, pieces[1], pieces[2])

    def _authenticate(self, tag, email, password):
        try:
            result = self.session.backend.login(email, password)
        except BackendError as e:
            # 不区分"用户不存在"与"密码错误" —— 后端已经这么做了，
            # 代理再区分一次等于把后端的防枚举设计白费
            LOG.info("IMAP 登录失败 %s: %s", email, e)
            self._no(tag, "登录失败：%s" % _one_line(str(e)))
            return

        self.session.token = result.get("token")
        self.session.user_email = result.get("email") or email
        self.session.user_id = result.get("userId")
        LOG.info("IMAP 登录成功 %s (uid=%s)", email, self.session.user_id)
        self._ok(tag, "登录成功")

    # ---------- 邮箱列表 ----------

    def _cmd_list(self, tag, args):
        return self._mailbox_list_response(tag)

    def _cmd_lsub(self, tag, args):
        return self._mailbox_list_response(tag)

    def _mailbox_list_response(self, tag):
        for name in MAILBOX_LIST:
            self.session.send_line('* LIST (\\HasNoChildren) "/" "%s"' % name)
        self._ok(tag)

    def _cmd_status(self, tag, args):
        if not self._require_auth(tag):
            return
        if not args:
            self._no(tag, "STATUS 需要邮箱名")
            return
        name = _text(args[0])
        entry = MAILBOX_ALIASES.get(name.lower())
        if entry is None:
            self._no(tag, "邮箱不存在: %s" % name)
            return

        box_type, canonical = entry
        try:
            messages = self.session.backend.list_all_mails(
                self.session.token, box_type, MAX_MESSAGES)
        except BackendError as e:
            self._no(tag, str(e))
            return

        unseen = sum(1 for m in messages if not m.get("isRead"))
        uidnext = _uidnext(messages)
        self.session.send_line(
            '* STATUS "%s" (MESSAGES %d UNSEEN %d UIDNEXT %d UIDVALIDITY %d)'
            % (canonical, len(messages), unseen, uidnext, self._uidvalidity()))
        self._ok(tag)

    def _uidvalidity(self):
        """用用户 ID 派生 UIDVALIDITY。

        它的语义是"UID 的命名空间变了，客户端必须重新同步"。本系统的 UID
        就是 mail.id，永不复用，因此一个用户内部它不需要变化；但换个账号登录
        时 UID 会指向完全不同的邮件，那时必须让客户端重来一次。
        """
        return UIDVALIDITY_BASE + int(self.session.user_id or 0)

    # ---------- 选中邮箱 ----------

    def _cmd_select(self, tag, args):
        self._select(tag, args, readonly=False)

    def _cmd_examine(self, tag, args):
        self._select(tag, args, readonly=True)

    def _select(self, tag, args, readonly):
        if not self._require_auth(tag):
            return
        if not args:
            self._no(tag, "SELECT 需要邮箱名")
            return

        name = _text(args[0])
        entry = MAILBOX_ALIASES.get(name.lower())
        if entry is None:
            self._no(tag, "邮箱不存在: %s" % name)
            return

        box_type, canonical = entry
        try:
            messages = self.session.backend.list_all_mails(
                self.session.token, box_type, MAX_MESSAGES)
        except BackendError as e:
            self._no(tag, str(e))
            return

        self.session.mailbox = canonical
        self.session.box_type = box_type
        self.session.messages = messages
        # 换邮箱后旧的会话态不再适用。留着会让上一个邮箱的 \Deleted 标记
        # 出现在新邮箱的邮件上（UID 是全局唯一的，但语义已经完全变了）
        self.session.deleted.clear()
        self.session.flagged.clear()
        self.session.seen_override.clear()
        self.session.raw_cache.clear()

        self.session.send_line("* FLAGS (\\Seen \\Answered \\Flagged \\Deleted \\Draft)")
        self.session.send_line("* %d EXISTS" % len(messages))
        # RECENT 已废弃，恒为 0。填真实值反而会让客户端按"新邮件"提示用户
        self.session.send_line("* 0 RECENT")
        self.session.send_line("* OK [UIDVALIDITY %d] UID 命名空间" % self._uidvalidity())
        self.session.send_line("* OK [UIDNEXT %d] 下一个 UID" % _uidnext(messages))
        self.session.send_line(
            "* OK [PERMANENTFLAGS (\\Seen \\Flagged \\Deleted)] 本客户端可持久化的标记")
        self._ok(tag, "[READ-ONLY] 已选中" if readonly else "[READ-WRITE] 已选中")

    def _cmd_close(self, tag, args):
        if self.session.box_type is None:
            self._no(tag, "尚未选中邮箱")
            return
        # CLOSE 的语义是"提交删除并回到未选中状态"
        self._expunge(tag, emit=False)
        self.session.mailbox = None
        self.session.box_type = None
        self.session.messages = []
        self._ok(tag)

    def _cmd_unselect(self, tag, args):
        self.session.mailbox = None
        self.session.box_type = None
        self.session.messages = []
        self._ok(tag)

    def _cmd_expunge(self, tag, args):
        if not self._require_selected(tag):
            return
        self._expunge(tag, emit=True)
        self._ok(tag)

    def _expunge(self, tag, emit):
        """真正删除被标记 \\Deleted 的邮件。

        从后往前删：每删一封，它后面所有邮件的序列号都会减一，
        而 EXPUNGE 响应里报的必须是**删除那一刻**的序列号。
        倒序处理时，尚未处理的邮件序列号都还没被前面的删除影响。
        """
        session = self.session
        targets = []
        for index in range(len(session.messages)):
            if session.uid_of(index) in session.deleted:
                targets.append(index)

        for index in reversed(targets):
            uid = session.uid_of(index)
            if emit:
                session.send_line("* %d EXPUNGE" % (index + 1))
            try:
                session.backend.delete_mail(session.token, uid)
            except BackendError as e:
                # 单封删除失败不该中断整轮：客户端已经把本地那封删了，
                # 报错只会让它卡在一个永远清不掉的状态
                LOG.warning("删除邮件 %s 失败: %s", uid, e)
            session.messages.pop(index)
            session.deleted.discard(uid)
            session.raw_cache.pop(uid, None)
            session.seen_override.pop(uid, None)

    # ---------- 取信 ----------

    def _cmd_fetch(self, tag, args):
        self._fetch(tag, args, by_uid=False)

    def _cmd_uid(self, tag, args):
        if not args:
            self._no(tag, "UID 需要子命令")
            return
        sub = _text(args[0]).upper()
        rest = args[1:]
        if sub == "FETCH":
            self._fetch(tag, rest, by_uid=True)
        elif sub == "SEARCH":
            self._search(tag, rest, by_uid=True)
        elif sub == "STORE":
            self._store(tag, rest, by_uid=True)
        else:
            self._no(tag, "不支持 UID %s" % sub)

    def _fetch(self, tag, args, by_uid):
        if not self._require_selected(tag):
            return
        if len(args) < 2:
            self._no(tag, "FETCH 需要序列号与项目")
            return

        indexes = self._resolve_indexes(_text(args[0]), by_uid)
        if indexes is None:
            self._no(tag, "序列号格式错误")
            return

        items = _text(args[1])
        for index in indexes:
            self._fetch_one(index, items)
        self._ok(tag)

    def _fetch_one(self, index, items):
        session = self.session
        uid = session.uid_of(index)

        # chunks 里两类元素：普通字符串直接拼进响应；元组 (标签, 字节) 表示
        # 一段字面量，要按 IMAP 的 {n}CRLF 语法单独写出
        chunks = []
        mark_seen = False

        for item in _split_fetch_items(items):
            upper = item.upper()
            if upper == "UID":
                chunks.append("UID %d" % uid)
            elif upper == "FLAGS":
                chunks.append("FLAGS %s" % session.flags_of(index))
            elif upper == "RFC822.SIZE":
                chunks.append("RFC822.SIZE %d" % len(session.raw_of(index)))
            elif upper == "INTERNALDATE":
                chunks.append('INTERNALDATE "%s"' % self._internal_date(index))
            elif upper == "ENVELOPE":
                chunks.append("ENVELOPE %s" % mime.envelope(mime.parse(session.raw_of(index))))
            elif upper == "BODYSTRUCTURE":
                chunks.append("BODYSTRUCTURE %s"
                              % mime.bodystructure(mime.parse(session.raw_of(index))))
            elif upper.startswith("BODY") or upper.startswith("RFC822"):
                content, label = self._body_section(index, item)
                chunks.append((label, content))
                # 不带 .PEEK 的预取在 IMAP 里意味着"用户看过了"。
                # 客户端同步时发的都是 .PEEK，因此这个分支很少走到 ——
                # 但真走到时（用户点开邮件）必须同步已读状态，
                # 否则 Web 端与客户端会显示不一致的未读数
                if ".PEEK" not in upper:
                    mark_seen = True

        self._write_fetch_response(index, chunks, mark_seen)

    def _write_fetch_response(self, index, chunks, mark_seen):
        session = self.session
        session.send_bytes(("* %d FETCH (" % (index + 1)).encode("ascii"))
        first = True
        for chunk in chunks:
            if not first:
                session.send_bytes(b" ")
            first = False
            if isinstance(chunk, tuple):
                label, data = chunk
                # 字面量：{n}CRLF 之后紧跟 n 字节。这里不能走 send_line，
                # 因为字面量里可能含任意二进制（附件）
                session.send_bytes((label + " {%d}\r\n" % len(data)).encode("ascii"))
                session.send_bytes(data)
            else:
                session.send_bytes(chunk.encode("utf-8", errors="replace"))
        session.send_bytes(b")\r\n")

        # 非 PEEK 的正文读取在 IMAP 里意味着"用户看过了"
        if mark_seen and not self.session.is_seen(index):
            self.session.seen_override[self.session.uid_of(index)] = True
            try:
                self.session.backend.set_read(self.session.token, self.session.uid_of(index), True)
            except BackendError as e:
                # 已读状态同步失败不影响这次读取，本地标记仍然生效
                LOG.warning("标记已读失败 %s: %s", self.session.uid_of(index), e)

    def _internal_date(self, index):
        raw = self.session.raw_of(index)
        message = mime.parse(raw)
        # 后端记录的时间作为兜底：信头 Date 缺失或畸形时用得上
        fallback = self.session.messages[index].get("sendTime")
        parsed_fallback = None
        if fallback:
            try:
                parsed_fallback = _parse_datetime(fallback)
            except Exception:
                parsed_fallback = None
        return mime.internal_date(message, parsed_fallback)

    def _body_section(self, index, item):
        """取出 BODY[...] 请求对应的内容，并给出应在响应里回显的标签。

        响应里的标签必须与客户端请求的写法**逐字对应**（大小写不敏感但结构一致），
        否则客户端会认为服务端答非所问，把那封邮件判为无法解析。
        """
        raw = self.session.raw_of(index)
        upper = item.upper()

        if upper.startswith("RFC822.HEADER"):
            header, _ = mime.split_header_body(raw)
            return header, "RFC822.HEADER"
        if upper.startswith("RFC822.TEXT"):
            _, body = mime.split_header_body(raw)
            return body, "RFC822.TEXT"
        if upper.startswith("RFC822"):
            return raw, "RFC822"

        # BODY / BODY.PEEK 后面跟的段落
        section = item[item.find("[") + 1:item.rfind("]")] if "[" in item else ""
        if not section:
            return raw, "BODY[]"

        section = section.strip()
        if section == "":
            return raw, "BODY[]"
        if section.upper() == "HEADER":
            header, _ = mime.split_header_body(raw)
            return header, "BODY[HEADER]"
        if section.upper() == "TEXT":
            _, body = mime.split_header_body(raw)
            return body, "BODY[TEXT]"
        if section.upper().startswith("HEADER.FIELDS"):
            names = _header_field_names(section)
            filtered = mime.pick_header_fields(raw, names)
            return filtered, "BODY[HEADER.FIELDS (%s)]" % " ".join(names)
        if section.upper().startswith("HEADER.FIELDS.NOT"):
            # 反向过滤用得极少（客户端偶尔用来排除大字段），
            # 简单起见按"全部头部"返回，客户端仍能正常显示
            header, _ = mime.split_header_body(raw)
            return header, "BODY[HEADER]"

        # 带段落号的多段请求（如 BODY[1]、BODY[1.2]）。
        # 后端只提供"整封报文"这一种粒度，这里统一回整封 ——
        # 客户端拿到完整报文后能自己定位段落，而回错内容会让它显示乱码
        return raw, "BODY[]"

    def _resolve_indexes(self, expr, by_uid):
        """把 IMAP 的序列集合表达式（1:5,7,*）翻译成下标列表。"""
        result = []
        for part in expr.split(","):
            part = part.strip()
            if not part:
                continue
            if ":" in part:
                low_text, high_text = part.split(":", 1)
                low = self._resolve_point(low_text, by_uid, default=1)
                high = self._resolve_point(high_text, by_uid, default=self._last_point(by_uid))
                if low is None or high is None:
                    return None
                if low > high:
                    low, high = high, low
                for point in range(low, high + 1):
                    index = self._point_to_index(point, by_uid)
                    if index is not None and index not in result:
                        result.append(index)
            else:
                point = self._resolve_point(part, by_uid, default=None)
                if point is None:
                    return None
                index = self._point_to_index(point, by_uid)
                if index is not None and index not in result:
                    result.append(index)

        result.sort()
        return result

    def _last_point(self, by_uid):
        """`*` 代表的那个值。

        按序号取时是"最后一封的位置"，按 UID 取时是"最大的 UID"——
        这两个数在本系统里并不相等（UID 是全局自增的 mail.id，
        一个只有 5 封邮件的收件箱里 UID 可能已经到 900 了）。
        混用会让 `UID FETCH 1:*` 只取到前几封，而客户端不会报错，
        只会显示"邮件少了"。
        """
        session = self.session
        if not session.messages:
            return 0
        if by_uid:
            return max(session.uid_of(i) for i in range(len(session.messages)))
        return len(session.messages)

    def _resolve_point(self, text, by_uid, default):
        text = text.strip()
        if text == "*":
            return self._last_point(by_uid)
        try:
            return int(text)
        except ValueError:
            return default

    def _point_to_index(self, point, by_uid):
        session = self.session
        if by_uid:
            return session.index_of_uid(point)
        if 1 <= point <= len(session.messages):
            return point - 1
        return None

    # ---------- 标记与检索 ----------

    def _cmd_store(self, tag, args):
        self._store(tag, args, by_uid=False)

    def _store(self, tag, args, by_uid):
        if not self._require_selected(tag):
            return
        if len(args) < 3:
            self._no(tag, "STORE 需要序列号、操作与标记")
            return

        indexes = self._resolve_indexes(_text(args[0]), by_uid)
        if indexes is None:
            self._no(tag, "序列号格式错误")
            return

        operation = _text(args[1]).upper()
        flags = _parse_flags(_text(args[2]))
        # .SILENT 后缀表示"不用回传新标记"，但回传了客户端也不会出错，
        # 因此两种写法同样处理，只把后缀去掉
        silent = ".SILENT" in operation
        operation = operation.replace(".SILENT", "")

        for index in indexes:
            uid = self.session.uid_of(index)
            for flag in flags:
                self._apply_flag(index, uid, flag, operation)

            if not silent:
                self.session.send_line("* %d FETCH (FLAGS %s)"
                                       % (index + 1, self.session.flags_of(index)))
        self._ok(tag)

    def _apply_flag(self, index, uid, flag, operation):
        session = self.session
        add = operation.startswith("+")
        remove = operation.startswith("-")

        if flag == "\\SEEN":
            if add:
                session.seen_override[uid] = True
                self._sync_read(uid, True)
            elif remove:
                session.seen_override[uid] = False
                self._sync_read(uid, False)
        elif flag == "\\DELETED":
            if add:
                session.deleted.add(uid)
            elif remove:
                session.deleted.discard(uid)
        elif flag == "\\FLAGGED":
            if add:
                session.flagged.add(uid)
            elif remove:
                session.flagged.discard(uid)

    def _sync_read(self, uid, read):
        try:
            self.session.backend.set_read(self.session.token, uid, read)
        except BackendError as e:
            LOG.warning("同步已读状态失败 uid=%s: %s", uid, e)

    def _cmd_search(self, tag, args):
        self._search(tag, args, by_uid=False)

    def _search(self, tag, args, by_uid):
        if not self._require_selected(tag):
            return
        criteria = _text(" ".join(_text(a) for a in args))
        try:
            indexes = self._match_search(criteria)
        except ValueError as e:
            self._no(tag, str(e))
            return

        if by_uid:
            found = [str(self.session.uid_of(i)) for i in indexes]
        else:
            found = [str(i + 1) for i in indexes]
        self.session.send_line("* SEARCH " + " ".join(found))
        self._ok(tag)

    def _match_search(self, criteria):
        """支持最常见的几个检索条件。

        不追求实现 RFC 3501 的全部条件：客户端在大多数场景下只用
        ALL / UNSEEN / SEEN / FROM / SUBJECT / SINCE / UID，而一个
        解析不了的复杂查询会退回 ALL —— 那比报错好，客户端至少还能显示。
        """
        tokens = criteria.split()
        index_list = list(range(len(self.session.messages)))
        pending = [t.upper() for t in tokens]

        i = 0
        while i < len(pending):
            token = pending[i]
            argument = tokens[i + 1] if i + 1 < len(tokens) else ""

            if token == "ALL":
                i += 1
            elif token == "UNSEEN":
                index_list = [n for n in index_list if not self.session.is_seen(n)]
                i += 1
            elif token == "SEEN":
                index_list = [n for n in index_list if self.session.is_seen(n)]
                i += 1
            elif token in ("FROM", "SUBJECT", "TO"):
                needle = argument.strip('"').lower()
                field = {"FROM": "senderEmail", "SUBJECT": "subject", "TO": "externalTo"}[token]
                index_list = [
                    n for n in index_list
                    if needle in str(self.session.messages[n].get(field) or "").lower()
                ]
                i += 2
            elif token == "UID":
                wanted = set()
                for part in argument.split(","):
                    if ":" in part:
                        low, high = part.split(":", 1)
                        try:
                            wanted.update(range(int(low), int(high) + 1))
                        except ValueError:
                            pass
                    else:
                        try:
                            wanted.add(int(part))
                        except ValueError:
                            pass
                index_list = [n for n in index_list if self.session.uid_of(n) in wanted]
                i += 2
            else:
                # 认不出的条件：忽略它而不是报错。宁可比客户端期望的多返回几封，
                # 也不要让它显示"搜索失败"—— 用户看到的是"这个客户端不好用"
                i += 1 if not argument else 2

        return index_list

    # ---------- 不支持的能力 ----------

    def _cmd_append(self, tag, args):
        # APPEND 是"把一封邮件保存到某个邮箱"。本系统没有对应接口：
        # 发信时后端会自动在「已发送」留一份，草稿则只能从 Web 端保存。
        # 明确回 NO，客户端会提示用户"无法保存到已发送"，而不是静默丢弃
        self._no(tag, "[CANNOT] 本代理不支持从客户端保存邮件"
                      "（发送的邮件会自动出现在「已发送」中）")


# ============================================================
# SMTP
# ============================================================

class SmtpHandler(socketserver.StreamRequestHandler):
    """SMTP 状态机。

    只实现"客户端发信"这一个方向所需的部分：EHLO/HELO、AUTH、MAIL、RCPT、
    DATA、RSET、NOOP、QUIT。不实现中继（不接收发给第三方的信）——
    一个开放的 SMTP 中继会被垃圾邮件发送者当成跳板。
    """

    def handle(self):
        self.token = None
        self.mail_from = None
        self.recipients = []
        LOG.info("SMTP 连接来自 %s", self.client_address)

        self._reply(220, "MailSystem proxy ready")

        while True:
            try:
                line = self.rfile.readline()
            except (ConnectionError, OSError):
                break
            if not line:
                break
            line = line.rstrip(b"\r\n")
            if line == b"":
                continue

            try:
                keep_going = self._dispatch(line)
            except (ConnectionError, OSError):
                break
            except Exception as e:  # noqa: BLE001
                LOG.exception("SMTP 命令处理异常")
                self._reply(451, "处理失败: %s" % _one_line(str(e)))
                continue
            if not keep_going:
                break

        LOG.info("SMTP 连接结束 %s", self.client_address)

    # ---------- 分发 ----------

    def _dispatch(self, line):
        text = line.decode("utf-8", errors="replace")
        parts = text.split(" ", 1)
        command = parts[0].upper()
        argument = parts[1] if len(parts) > 1 else ""

        handler = getattr(self, "_cmd_" + command.lower(), None)
        if handler is None:
            self._reply(502, "不支持的命令 %s" % command)
            return True
        return handler(argument)

    def _reply(self, code, text):
        self.wfile.write(("%d %s\r\n" % (code, text)).encode("utf-8", errors="replace"))
        self.wfile.flush()

    # ---------- 会话 ----------

    def _cmd_ehlo(self, argument):
        # 不声明 STARTTLS：声明了客户端会尝试升级，而代理不做 TLS。
        # 需要加密时应在代理前面套 TLS 终结（见 README）
        self.wfile.write(
            b"250-mail-system-proxy\r\n"
            b"250-AUTH PLAIN LOGIN\r\n"
            b"250-8BITMIME\r\n"
            b"250-SIZE 26214400\r\n"
            b"250 HELP\r\n")
        self.wfile.flush()
        return True

    def _cmd_helo(self, argument):
        self._reply(250, "mail-system-proxy")
        return True

    def _cmd_noop(self, argument):
        self._reply(250, "OK")
        return True

    def _cmd_rset(self, argument):
        self.mail_from = None
        self.recipients = []
        self._reply(250, "OK")
        return True

    def _cmd_vrfy(self, argument):
        # 不校验地址存在性：那会把这个端口变成"探测本系统有哪些用户"的工具
        self._reply(252, "无法验证，但会尝试投递")
        return True

    def _cmd_quit(self, argument):
        self._reply(221, "再见")
        return False

    def _cmd_auth(self, argument):
        if self.token:
            self._reply(503, "已经登录过了")
            return True

        pieces = argument.split(" ", 1)
        mechanism = pieces[0].upper() if pieces else ""

        if mechanism == "PLAIN":
            if len(pieces) > 1:
                return self._auth_plain(pieces[1])
            self._reply(334, "")
            initial = self.rfile.readline().rstrip(b"\r\n")
            return self._auth_plain(initial.decode("ascii", errors="replace"))

        if mechanism == "LOGIN":
            self._reply(334, base64.b64encode(b"Username:").decode("ascii"))
            raw_user = self.rfile.readline().rstrip(b"\r\n")
            self._reply(334, base64.b64encode(b"Password:").decode("ascii"))
            raw_pass = self.rfile.readline().rstrip(b"\r\n")
            try:
                user = base64.b64decode(raw_user).decode("utf-8")
                password = base64.b64decode(raw_pass).decode("utf-8")
            except Exception:
                self._reply(501, "认证数据不是合法的 Base64")
                return True
            return self._login(user, password)

        self._reply(504, "只支持 AUTH PLAIN 与 AUTH LOGIN")
        return True

    def _auth_plain(self, encoded):
        try:
            decoded = base64.b64decode(encoded).decode("utf-8")
        except Exception:
            self._reply(501, "认证数据不是合法的 Base64")
            return True
        fields = decoded.split("\0")
        if len(fields) < 3:
            self._reply(501, "认证数据格式错误")
            return True
        return self._login(fields[1], fields[2])

    def _login(self, email, password):
        try:
            result = self.server.backend.login(email, password)
        except BackendError as e:
            LOG.info("SMTP 登录失败 %s: %s", email, e)
            self._reply(535, "登录失败：%s" % _one_line(str(e)))
            return True

        self.token = result.get("token")
        LOG.info("SMTP 登录成功 %s", email)
        self._reply(235, "认证成功")
        return True

    # ---------- 投递 ----------

    def _cmd_mail(self, argument):
        if not self.token:
            self._reply(530, "请先认证（AUTH）")
            return True
        address = _envelope_address(argument)
        if not address:
            self._reply(501, "MAIL FROM 地址格式错误")
            return True
        self.mail_from = address
        self.recipients = []
        self._reply(250, "OK")
        return True

    def _cmd_rcpt(self, argument):
        if not self.mail_from:
            self._reply(503, "请先发 MAIL FROM")
            return True
        address = _envelope_address(argument)
        if not address:
            self._reply(501, "RCPT TO 地址格式错误")
            return True
        self.recipients.append(address)
        self._reply(250, "OK")
        return True

    def _cmd_data(self, argument):
        if not self.recipients:
            self._reply(503, "没有收件人")
            return True
        self._reply(354, "开始输入邮件内容，以 <CRLF>.<CRLF> 结束")

        try:
            raw = self._read_data()
        except (ConnectionError, OSError):
            return False

        try:
            self._deliver(raw)
        except BackendError as e:
            LOG.warning("投递失败: %s", e)
            # 后端拒绝的原因（如"收件人不存在"）必须原样转给客户端，
            # 否则用户看到的是"发送失败"却不知道为什么
            self._reply(554, "投递失败：%s" % _one_line(str(e)))
            return True
        except Exception as e:  # noqa: BLE001
            LOG.exception("投递异常")
            self._reply(451, "投递异常：%s" % _one_line(str(e)))
            return True

        self.mail_from = None
        self.recipients = []
        self._reply(250, "邮件已发送")
        return True

    def _read_data(self):
        """读取 DATA 段，直到单独一行的 '.'。

        同时做**点透明性还原**：SMTP 规定行首的 '.' 要重复一次（`..`）来与
        结束标记区分，收信方必须还原。少了这一步，正文里以点开头的行会多出一个点。
        """
        lines = []
        while True:
            line = self.rfile.readline()
            if not line:
                raise ConnectionError("DATA 过程中连接中断")
            if line in (b".\r\n", b".\n"):
                break
            if line.startswith(b".."):
                line = line[1:]
            lines.append(line)
        return b"".join(lines)

    def _deliver(self, raw):
        from email.utils import getaddresses

        message = email.message_from_bytes(raw)
        subject = _decode_header_value(message.get("Subject")) or "(无主题)"
        body, attachments = _extract_body_and_attachments(message)

        # 收件人按信头的意图拆分：To 里的当收件人，Cc 里的当抄送。
        # 信封上的 RCPT TO 里若有信头没提到的地址（Bcc），
        # 一并算作收件人 —— 否则密送的那个人收不到信
        header_to = {addr.lower() for _, addr in getaddresses([message.get("To") or ""])}
        header_cc = {addr.lower() for _, addr in getaddresses([message.get("Cc") or ""])}

        receivers = []
        cc = []
        for address in self.recipients:
            lowered = address.lower()
            if lowered in header_cc and lowered not in header_to:
                cc.append(address)
            else:
                receivers.append(address)

        if not receivers and cc:
            # 全是抄送时后端会因"收件人不能为空"而拒绝，把它们都当收件人
            receivers, cc = cc, []

        attachment_ids = []
        for filename, content_type, data in attachments:
            attachment_ids.append(
                self.server.backend.upload_attachment(self.token, filename, content_type, data))

        LOG.info("投递 → %s (抄送 %s) 主题=%s 附件=%d",
                 receivers, cc, subject, len(attachment_ids))
        self.server.backend.send(
            self.token,
            receivers=",".join(receivers),
            cc=",".join(cc),
            subject=subject,
            body=body,
            attachment_ids=attachment_ids,
        )


def _extract_body_and_attachments(message):
    """从 MIME 报文里取出正文与附件。

    正文优先取 text/plain：这是邮件客户端最保真的表达方式。
    只有 HTML 时退回 HTML 原文 —— 后端的正文列本来就允许存 HTML
    （Web 端发信时就是 HTML）。
    """
    plain_parts = []
    html_parts = []
    attachments = []

    for part in message.walk():
        if part.is_multipart():
            continue

        disposition = part.get_content_disposition()
        filename = part.get_filename()
        if disposition == "attachment" or (filename and disposition != "inline"):
            data = part.get_payload(decode=True)
            if data:
                attachments.append((
                    _decode_header_value(filename) or "attachment",
                    part.get_content_type(),
                    data,
                ))
            continue

        if part.get_content_maintype() != "text":
            continue

        payload = part.get_payload(decode=True)
        if not payload:
            continue
        charset = part.get_content_charset() or "utf-8"
        try:
            text = payload.decode(charset, errors="replace")
        except LookupError:
            # 报了一个不存在的字符集名，用 UTF-8 兜底比整封信丢掉强
            text = payload.decode("utf-8", errors="replace")

        if part.get_content_subtype() == "plain":
            plain_parts.append(text)
        else:
            html_parts.append(text)

    body = "\n".join(plain_parts).strip()
    if not body:
        body = "\n".join(html_parts).strip()
    return body, attachments


# ============================================================
# 服务器
# ============================================================

class ImapServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address, backend):
        self.backend = backend
        super().__init__(address, ImapHandler)


class SmtpServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address, backend):
        self.backend = backend
        super().__init__(address, SmtpHandler)


# ============================================================
# 工具函数
# ============================================================

def _tokenize(line):
    """把一行 IMAP 命令拆成 token。

    quoted string 会被去掉引号并还原转义；括号列表（如 `(FLAGS UID)`）
    作为**一个** token 返回 —— 命令处理里需要它的原始形态。
    """
    tokens = []
    current = bytearray()
    i = 0
    length = len(line)

    while i < length:
        char = line[i:i + 1]
        if char == b'"':
            i += 1
            while i < length:
                if line[i:i + 1] == b"\\" and i + 1 < length:
                    current += line[i + 1:i + 2]
                    i += 2
                    continue
                if line[i:i + 1] == b'"':
                    i += 1
                    break
                current += line[i:i + 1]
                i += 1
        elif char == b"(":
            depth = 1
            current += char
            i += 1
            while i < length and depth > 0:
                char = line[i:i + 1]
                if char == b'"':
                    current += char
                    i += 1
                    while i < length and line[i:i + 1] != b'"':
                        current += line[i:i + 1]
                        i += 1
                    if i < length:
                        current += b'"'
                        i += 1
                    continue
                if char == b"(":
                    depth += 1
                elif char == b")":
                    depth -= 1
                current += char
                i += 1
        elif char == b" ":
            if current:
                tokens.append(bytes(current))
                current = bytearray()
            i += 1
        else:
            current += char
            i += 1

    if current:
        tokens.append(bytes(current))
    return tokens


def _text(token):
    return token.decode("utf-8", errors="replace") if isinstance(token, bytes) else str(token)


def _split_fetch_items(items):
    """拆开 FETCH 的项目列表。

    `BODY.PEEK[HEADER.FIELDS (FROM TO)]` 里的空格在括号内，不能按空格硬拆 ——
    那样会把一个项目切成两半，客户端会收到一个无法解析的响应。

    客户端一般会把整个列表再包一层括号（`(UID FLAGS BODY[])`），
    那层括号是语法上的分组、不是项目的一部分，必须先剥掉。
    """
    result = []
    current = ""
    depth = 0
    for char in _strip_outer_parens(items):
        if char == "[":
            depth += 1
        elif char == "]":
            depth -= 1
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1

        if char == " " and depth == 0:
            if current:
                result.append(current)
            current = ""
        else:
            current += char
    if current:
        result.append(current)

    # `BODY` 单独出现等价于 `BODYSTRUCTURE`
    return [item if item.upper() != "BODY" else "BODYSTRUCTURE" for item in result]


def _strip_outer_parens(text):
    """剥掉整体包裹的一层括号。

    只有"首尾括号配对、且中途没有提前闭合"时才算整体包裹 ——
    否则 `BODY[HEADER.FIELDS (FROM)]` 这种内容里带括号的单个项目
    会被削掉两头，变成非法语法。
    """
    text = text.strip()
    if not (text.startswith("(") and text.endswith(")")):
        return text

    depth = 0
    for position, char in enumerate(text):
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0 and position != len(text) - 1:
                # 括号在中途就闭合了，说明不是整体包裹
                return text
    return text[1:-1] if depth == 0 else text


def _header_field_names(section):
    """从 `HEADER.FIELDS (FROM SUBJECT)` 里取出字段名列表。"""
    start = section.find("(")
    end = section.rfind(")")
    if start < 0 or end < 0:
        return ["FROM", "SUBJECT", "DATE", "TO", "CC", "MESSAGE-ID"]
    inner = section[start + 1:end]
    names = [name.strip().upper() for name in inner.split() if name.strip()]
    return names or ["FROM", "SUBJECT", "DATE", "TO", "CC", "MESSAGE-ID"]


def _parse_flags(text):
    """从 `(\\Seen \\Deleted)` 里取出标记名，统一成大写。"""
    cleaned = text.strip().strip("()")
    return [flag.strip().upper() for flag in cleaned.split() if flag.strip()]


def _uidnext(messages):
    """下一个可用的 UID。没有邮件时给 1，否则是最大 UID + 1。"""
    highest = 0
    for message in messages:
        try:
            highest = max(highest, int(message.get("id")))
        except (TypeError, ValueError):
            continue
    return highest + 1


def _decode_header_value(value):
    if not value:
        return None
    try:
        return str(make_header(decode_header(value)))
    except Exception:
        return value


def _envelope_address(argument):
    """从 `FROM:<a@b.com>` 里取出地址。"""
    match = re.search(r"<([^>]*)>", argument)
    if match:
        return match.group(1).strip() or None
    parts = argument.split(":", 1)
    if len(parts) == 2 and "@" in parts[1]:
        return parts[1].strip()
    return None


def _one_line(text):
    """把可能含换行的错误信息压成一行 —— SMTP/IMAP 的响应体不能含裸换行，
    否则客户端会把它当成两条响应，整个会话就此错位。"""
    return " ".join(str(text).split())[:200]


def _parse_datetime(text):
    """解析后端返回的时间串（形如 `2026-09-23 10:30:00`）。

    后端按 Asia/Shanghai 输出且不带时区标记，因此补上本机时区 ——
    不加的话这个时间会被当成 UTC，邮件时间差 8 小时。
    """
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S"):
        try:
            return datetime.strptime(text[:19], fmt).astimezone()
        except ValueError:
            continue
    raise ValueError("无法解析时间: %s" % text)


# ============================================================
# 入口
# ============================================================

def main(argv=None):
    parser = argparse.ArgumentParser(
        description="邮件系统的 SMTP/IMAP 协议代理（无需任何第三方依赖）")
    parser.add_argument("--backend", default=os.environ.get(
        "BACKEND_URL", "http://localhost:8080"),
        help="后端地址。Docker 环境下是 http://backend:8080（默认 localhost:8080）")
    parser.add_argument("--host", default=os.environ.get("PROXY_HOST", "0.0.0.0"),
                        help="监听地址（默认 0.0.0.0）")
    parser.add_argument("--imap-port", type=int,
                        default=int(os.environ.get("IMAP_PORT", "143")),
                        help="IMAP 端口（默认 143）")
    parser.add_argument("--smtp-port", type=int,
                        default=int(os.environ.get("SMTP_PORT", "2525")),
                        help="SMTP 端口（默认 2525）")
    parser.add_argument("--verbose", action="store_true",
                        default=os.environ.get("PROXY_VERBOSE", "").lower() == "true",
                        help="打印每个协议命令，排查客户端兼容性问题时用")
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    backend = Backend(args.backend)
    imap = ImapServer((args.host, args.imap_port), backend)
    smtp = SmtpServer((args.host, args.smtp_port), backend)

    LOG.info("后端地址 %s", args.backend)
    LOG.info("IMAP 监听 %s:%d", args.host, args.imap_port)
    LOG.info("SMTP 监听 %s:%d", args.host, args.smtp_port)

    imap_thread = threading.Thread(target=imap.serve_forever, name="imap", daemon=True)
    imap_thread.start()

    try:
        smtp.serve_forever()
    except KeyboardInterrupt:
        LOG.info("收到中断信号，正在退出")
    finally:
        smtp.shutdown()
        imap.shutdown()

    return 0


if __name__ == "__main__":
    sys.exit(main())
