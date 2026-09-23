# ============================================================
# SMTP / IMAP 协议代理
#
# 用 alpine 而非 Debian 系：整个镜像里只有一个 Python 解释器和三个 .py 文件，
# 镜像体积约 50MB，重建一次只要几秒。
# ============================================================
FROM python:3.12-alpine

# 以非 root 运行：代理会监听 143/2525 这两个非特权端口，不需要 root，
# 而一旦被攻破（它对外暴露的是邮件协议端口），root 与非 root 的差别就是
# "整个容器"还是"容器里的一个空账号"
RUN adduser -D -H -u 10001 proxy

WORKDIR /app
COPY backend.py mime.py server.py ./

USER proxy

# 143 是 IMAP 的标准端口，2525 是 SMTP 的备用端口（25 常被云主机封禁，
# 且没有任何可用的鉴权与加密保障）
EXPOSE 143 2525

# 不写 HEALTHCHECK：健康检查需要一个 IMAP 应答，而用 nc/curl 探测会往日志里
# 留下一串"协议错误"。容器编排层的存活判断交给进程退出码即可

CMD ["python", "-u", "server.py"]
