# ============================================================
# Redis 生产环境 Dockerfile
# 基于官方 Redis 7 Alpine 镜像，应用自定义配置
# ============================================================
FROM redis:7-alpine

# 复制生产环境配置文件到容器
# 【生产环境替换项】如需调整持久化策略或内存限制，请同步修改 redis.conf
COPY redis.conf /usr/local/etc/redis/redis.conf

# 创建持久化数据目录并设置权限
RUN mkdir -p /data && chown redis:redis /data

# 使用自定义配置文件启动 Redis
CMD [ "redis-server", "/usr/local/etc/redis/redis.conf" ]

# 健康检查 — 定期 ping Redis 确认服务可用
HEALTHCHECK --interval=10s --timeout=5s --retries=3 \
    CMD redis-cli -a $REDIS_PASSWORD ping || exit 1
