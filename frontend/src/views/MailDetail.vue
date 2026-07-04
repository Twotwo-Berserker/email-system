<template>
  <div class="detail-page" v-loading="loading">
    <template v-if="mail">
      <!-- 操作栏 -->
      <div class="detail-toolbar">
        <el-button @click="$router.back()">
          <el-icon><ArrowLeft /></el-icon> 返回
        </el-button>
        <el-button @click="handleReply">
          <el-icon><ChatDotRound /></el-icon> 回复
        </el-button>
        <el-button @click="handleForward">
          <el-icon><Share /></el-icon> 转发
        </el-button>
        <el-button v-if="isFromTrash" type="success" :loading="restoring" @click="handleRestore">
          <el-icon><Upload /></el-icon> 恢复
        </el-button>
        <el-button @click="handleToggleRead" :loading="togglingRead">
          <el-icon><Reading /></el-icon> {{ mail.isRead ? '标记未读' : '标记已读' }}
        </el-button>
        <el-button type="danger" @click="handleDelete">
          <el-icon><Delete /></el-icon> 删除
        </el-button>
      </div>

      <!-- 邮件主题 -->
      <h2 class="detail-subject">
        <el-tag v-if="mail.priority >= 70" type="danger" size="small">高优先级</el-tag>
        <el-tag v-if="mail.isSpam" type="warning" size="small" style="margin-left:6px">垃圾邮件</el-tag>
        {{ mail.subject }}
      </h2>

      <!-- 发件人信息 -->
      <div class="detail-meta">
        <div class="meta-row">
          <span class="meta-label">发件人:</span>
          <span class="meta-value">{{ mail.senderEmail }}</span>
        </div>
        <div class="meta-row">
          <span class="meta-label">时间:</span>
          <span class="meta-value">{{ mail.sendTime }}</span>
        </div>
        <div class="meta-row" v-if="mail.receiverNames || mail.receiverIds">
          <span class="meta-label">收件人:</span>
          <span class="meta-value">{{ mail.receiverNames || mail.receiverIds }}</span>
        </div>
        <div class="meta-row" v-if="mail.ccNames || mail.ccIds">
          <span class="meta-label">抄送:</span>
          <span class="meta-value">{{ mail.ccNames || mail.ccIds }}</span>
        </div>
        <div class="meta-row" v-if="mail.category">
          <span class="meta-label">智能分类:</span>
          <el-tag size="small" type="info">{{ mail.category }}</el-tag>
        </div>
        <div class="meta-row" v-if="mail.summary">
          <span class="meta-label">AI摘要:</span>
          <span class="meta-value" style="color:#909399;font-style:italic">{{ mail.summary }}</span>
        </div>
      </div>

      <el-divider />

      <!-- 邮件正文 -->
      <div class="detail-body">
        <div v-if="isHtmlBody" v-html="mail.body"></div>
        <pre v-else class="plain-body">{{ mail.body }}</pre>
      </div>

      <!-- 附件列表 -->
      <div v-if="attachments.length > 0" class="detail-attachments">
        <el-divider />
        <h3>附件 ({{ attachments.length }})</h3>
        <div class="attachment-list">
          <div
            v-for="att in attachments"
            :key="att.id"
            class="attachment-item"
          >
            <el-icon :size="20"><component :is="getFileIcon(att.fileName)" /></el-icon>
            <span class="att-name">{{ att.fileName }}</span>
            <span class="att-size">{{ formatFileSize(att.fileSize) }}</span>
            <el-button type="primary" link size="small" @click="handleDownload(att)">
              下载
            </el-button>
            <el-button link size="small" @click="handlePreview(att)">
              预览
            </el-button>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getFileIcon } from '@/utils'
import { mailDetail, mailAttachments, markAsRead, toggleMailRead, deleteMail, restoreMail } from '@/api/mail'
import { downloadAttachment, previewAttachment } from '@/api/attachment'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, ChatDotRound, Upload, Share, Reading } from '@element-plus/icons-vue'
import { useMailActions } from '@/composables/useMailActions'
import { useLocalCache } from '@/composables/useLocalCache'
import { useUserStore } from '@/stores/user'

const { permanentDeleteWithConfirm } = useMailActions()
const { invalidateMailCache } = useLocalCache()
const userStore = useUserStore()

const route = useRoute()
const router = useRouter()
const mail = ref(null)
const attachments = ref([])
const loading = ref(true)
const restoring = ref(false)
const togglingRead = ref(false)

const isFromTrash = computed(() => route.query.from === 'trash')

const isHtmlBody = computed(() => {
  return mail.value?.body?.includes('<') && mail.value?.body?.includes('>')
})

onMounted(async () => {
  const id = route.params.id
  try {
    const [mailRes, attRes] = await Promise.all([
      mailDetail(id),
      mailAttachments(id)
    ])
    mail.value = mailRes.data
    attachments.value = attRes.data || []
    await markAsRead(id)
    // 使缓存失效
    if (userStore.userInfo?.id) {
      invalidateMailCache(userStore.userInfo.id)
    }
  } catch (e) {
    ElMessage.error('加载邮件失败')
  } finally {
    loading.value = false
  }
})

function handleReply() {
  router.push({
    path: '/compose',
    query: {
      to: mail.value.senderEmail,
      subject: `Re: ${mail.value.subject}`
    }
  })
}

function handleForward() {
  router.push({
    path: '/compose',
    query: {
      forwardId: mail.value.id
    }
  })
}

async function handleToggleRead() {
  togglingRead.value = true
  try {
    const res = await toggleMailRead(mail.value.id)
    mail.value.isRead = res.data ? 1 : 0
    ElMessage.success(res.data ? '已标记为已读' : '已标记为未读')
    if (userStore.userInfo?.id) {
      invalidateMailCache(userStore.userInfo.id)
    }
  } catch (e) {
    ElMessage.error('操作失败')
  } finally {
    togglingRead.value = false
  }
}

async function handleRestore() {
  restoring.value = true
  try {
    await restoreMail(mail.value.id)
    ElMessage.success('邮件已恢复到收件箱')
    router.back()
  } catch (e) {
    // error handled by interceptor
  } finally {
    restoring.value = false
  }
}

async function handleDelete() {
  if (isFromTrash.value) {
    const deleted = await permanentDeleteWithConfirm(mail.value.id)
    if (deleted) {
      router.back()
    }
  } else {
    await ElMessageBox.confirm('确定删除该邮件吗？', '提示', { type: 'warning' })
    try {
      await deleteMail(mail.value.id)
      ElMessage.success('已删除')
      router.back()
    } catch (e) {
      // 取消
    }
  }
}

async function handleDownload(att) {
  try {
    await downloadAttachment(att.id, att.fileName)
  } catch (e) {
    ElMessage.error('下载失败')
  }
}

async function handlePreview(att) {
  try {
    await previewAttachment(att.id)
  } catch (e) {
    ElMessage.error('预览失败')
  }
}

function formatFileSize(bytes) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return size.toFixed(1) + ' ' + units[i]
}
</script>

<style scoped>
.detail-page {
  max-width: 900px;
}

.detail-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 20px;
}

.detail-subject {
  font-size: 22px;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.detail-meta {
  background: #f5f7fa;
  padding: 16px;
  border-radius: 8px;
  margin-bottom: 12px;
}

.meta-row {
  margin-bottom: 6px;
  font-size: 14px;
}

.meta-label {
  color: #909399;
  display: inline-block;
  width: 70px;
}

.meta-value {
  color: #303133;
}

.detail-body {
  min-height: 200px;
  padding: 16px;
  line-height: 1.8;
  font-size: 15px;
}

.plain-body {
  white-space: pre-wrap;
  font-family: inherit;
  line-height: 1.8;
}

.detail-attachments {
  margin-top: 16px;
}

.attachment-list {
  margin-top: 12px;
}

.attachment-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 6px;
  margin-bottom: 8px;
}

.att-name {
  flex: 1;
}

.att-size {
  color: #909399;
  font-size: 13px;
}
</style>
