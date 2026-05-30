# ============================================================
# 主 Nginx Dockerfile — 反向代理 + 静态资源服务
# 静态文件通过 docker-compose 的 frontend-dist 命名卷挂载
# ============================================================

FROM nginx:1.25-alpine

# 安装 curl 用于健康检查
RUN apk add --no-cache curl

# 复制自定义 Nginx 配置
COPY default.conf /etc/nginx/conf.d/default.conf

# 确保静态资源目录存在（实际内容由卷挂载提供）
RUN mkdir -p /usr/share/nginx/html

EXPOSE 80

# Nginx 前台运行
CMD ["nginx", "-g", "daemon off;"]
