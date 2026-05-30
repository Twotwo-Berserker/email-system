<template>
  <div class="trash-page">
    <div class="toolbar">
      <h2>🗑️ 垃圾箱</h2>
      <div>
        <el-button @click="refreshMails">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
        <el-button type="danger" plain :disabled="mails.length === 0" @click="handleEmptyTrash">
          <el-icon><Delete /></el-icon> 清空垃圾箱
        </el-button>
      </div>
    </div>

    <div class="mail-list" v-loading="loading">
      <template v-if="mails.length > 0">
        <div
          v-for="mail in mails"
          :key="mail.id"
          class="mail-item"
        >
          <div class="mail-item-left" @click="$router.push(`/mail/${mail.id}?from=trash`)">
            <span class="mail-sender">{{ mail.senderEmail }}</span>
          </div>
          <div class="mail-item-center" @click="$router.push(`/mail/${mail.id}?from=trash`)">
            <span class="mail-subject">{{ mail.subject }}</span>
          </div>
          <div class="mail-item-right">
            <span class="mail-time">{{ formatTime(mail.sendTime) }}</span>
            <div class="mail-actions" @click.stop>
              <el-button
                type="primary"
                size="small"
                text
                @click="handleRestore(mail.id)"
              >
                <el-icon><Upload /></el-icon> 恢复
              </el-button>
              <el-button
                type="danger"
                size="small"
                text
                @click="handlePermanentDelete(mail.id)"
              >
                <el-icon><Delete /></el-icon> 删除
              </el-button>
            </div>
          </div>
        </div>
      </template>
      <el-empty v-else description="垃圾箱为空" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { listMails, restoreMail, emptyTrash } from '@/api/mail'
import { formatTime } from '@/utils'
import { useMailActions } from '@/composables/useMailActions'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Upload, Refresh } from '@element-plus/icons-vue'

const { permanentDeleteWithConfirm } = useMailActions()

const mails = ref([])
const loading = ref(false)

onMounted(() => refreshMails())

async function refreshMails() {
  loading.value = true
  try {
    const res = await listMails(3)
    mails.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function handleRestore(id) {
  try {
    await restoreMail(id)
    ElMessage.success('邮件已恢复到收件箱')
    await refreshMails()
  } catch (e) {
    // error handled by interceptor
  }
}

async function handlePermanentDelete(id) {
  const deleted = await permanentDeleteWithConfirm(id)
  if (deleted) {
    await refreshMails()
  }
}

async function handleEmptyTrash() {
  try {
    await ElMessageBox.confirm(
      `确定要清空垃圾箱中的所有 ${mails.value.length} 封邮件吗？清空后将无法恢复。`,
      '清空垃圾箱',
      {
        confirmButtonText: '确定清空',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    await emptyTrash()
    ElMessage.success('垃圾箱已清空')
    await refreshMails()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
  }
}
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.toolbar > div {
  display: flex;
  gap: 8px;
}

.mail-item {
  display: flex;
  align-items: center;
  padding: 12px 8px;
  border-bottom: 1px solid #f0f0f0;
  transition: background-color 0.2s;
  gap: 12px;
}

.mail-item:hover {
  background-color: #f5f7fa;
}

.mail-item-left {
  min-width: 180px;
  cursor: pointer;
}

.mail-sender {
  color: #606266;
}

.mail-item-center {
  flex: 1;
  overflow: hidden;
  cursor: pointer;
}

.mail-subject {
  font-weight: 500;
}

.mail-item-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.mail-time {
  color: #909399;
  font-size: 13px;
}

.mail-actions {
  display: flex;
  gap: 4px;
  opacity: 0.6;
  transition: opacity 0.2s;
}

.mail-item:hover .mail-actions {
  opacity: 1;
}
</style>
