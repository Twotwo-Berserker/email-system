# ============================================================
# 前端 Dockerfile — 多阶段构建
# 阶段1: Node 构建 Vue3 前端 → dist/
# 阶段2: 将构建产物复制到共享卷（供 Nginx 使用）
# ============================================================

# -------------------- 阶段1: 前端构建 --------------------
FROM node:18-alpine AS builder

WORKDIR /app

# 复制依赖文件（利用 Docker 缓存层加速构建）
COPY package.json package-lock.json ./

# 安装依赖（使用 npm ci 保证可重复构建）
RUN npm ci --registry=https://registry.npmmirror.com

# 复制源码
COPY . .

# 构建生产版本 → dist/
RUN npm run build

# -------------------- 阶段2: 产物导出 --------------------
FROM alpine:latest

# 从构建阶段复制 dist 产物
COPY --from=builder /app/dist /dist

# 将产物复制到共享卷（docker-compose 挂载的 /output）
# 此容器执行完毕后自动退出，产物保留在命名卷中供 Nginx 使用
# 使用 /dist/. 确保包括隐藏文件在内的所有文件都被复制
ENTRYPOINT ["sh", "-c", "cp -r /dist/. /output/ && echo 'Frontend build completed successfully'"]
