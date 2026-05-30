# ============================================================
# SpringBoot 后端 Dockerfile — 多阶段构建
# 阶段1: Maven 构建（使用 Maven 缓存加速）
# 阶段2: JRE 运行（精简镜像体积）
# ============================================================

# -------------------- 阶段1: 构建 --------------------
FROM maven:3.9-amazoncorretto-21 AS builder

WORKDIR /build

# 先复制 pom.xml，利用 Docker 缓存层加速构建
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 复制源码并构建
COPY src ./src
RUN mvn clean package -DskipTests -B

# -------------------- 阶段2: 运行 --------------------
# 使用轻量级 JRE 镜像
FROM amazoncorretto:21-alpine AS runtime

WORKDIR /app

# 创建非 root 用户运行应用（安全最佳实践）
RUN addgroup -g 1000 appgroup && \
    adduser -u 1000 -G appgroup -D appuser

# 创建上传缓存目录（MinIO 模式下的临时缓存，不依赖宿主机挂载）
RUN mkdir -p /app/uploads && chown -R appuser:appgroup /app

# 安装 curl 用于健康检查
RUN apk add --no-cache curl

# 从构建阶段复制 JAR 包
COPY --from=builder /build/target/*.jar app.jar

# 切换到非 root 用户
USER appuser

# 暴露端口
EXPOSE 8080

# JVM 参数优化
# 【生产环境替换项】根据服务器内存调整 -Xmx 和 -Xms
ENTRYPOINT ["java", \
    "-Xms256m", \
    "-Xmx512m", \
    "-XX:+UseG1GC", \
    "-XX:MaxGCPauseMillis=200", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
