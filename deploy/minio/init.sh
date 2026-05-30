#!/bin/sh
# ============================================================
# MinIO 初始化脚本
# 用途: 创建默认存储桶并设置访问策略
# 执行时机: MinIO 服务启动后首次运行
# ============================================================

set -e

# MinIO 客户端别名配置
# 【生产环境替换项】endpoint_url 默认使用 Docker 内部网络地址
# 如果 MinIO 部署在独立服务器上，请替换为实际地址
mc alias set local http://minio:9000 ${MINIO_ROOT_USER} ${MINIO_ROOT_PASSWORD}

# -------------------- 创建存储桶 --------------------
# 邮件附件存储桶
# 【生产环境替换项】如需修改存储桶名称，请同步修改 application.yml 中的 minio.bucket-name
echo "Creating bucket: mail-attachments"
mc mb local/mail-attachments --ignore-existing

# 邮件系统通用文件存储桶（备份、导出等）
echo "Creating bucket: mail-system"
mc mb local/mail-system --ignore-existing

# -------------------- 设置存储桶访问策略 --------------------
# mail-attachments: 私有读写（仅通过后端 API 访问，签名 URL 下载）
# 附件属于敏感数据，设置为 private
echo "Setting access policy for mail-attachments to private"
mc anonymous set private local/mail-attachments

# mail-system: 私有（内部使用）
echo "Setting access policy for mail-system to private"
mc anonymous set private local/mail-system

# -------------------- 存储桶生命周期规则（可选）--------------------
# 设置附件自动清理策略（180天后删除未绑定的孤立文件）
# 注释掉如需禁用自动清理
echo "Setting lifecycle policy for mail-attachments"
mc ilm import local/mail-attachments <<EOF
{
  "Rules": [
    {
      "ID": "expire-orphaned-attachments",
      "Status": "Enabled",
      "Filter": {
        "Prefix": "orphaned/"
      },
      "Expiration": {
        "Days": 180
      }
    }
  ]
}
EOF

echo "============================================"
echo " MinIO initialization completed"
echo " Buckets created: mail-attachments, mail-system"
echo " Access policies: private (both buckets)"
echo "============================================"
