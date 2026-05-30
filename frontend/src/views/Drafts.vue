<template>
  <div class="drafts-page">
    <div class="toolbar">
      <h2>📝 草稿箱</h2>
      <div>
        <el-button type="primary" @click="$router.push('/compose')">
          <el-icon><Edit /></el-icon> 写邮件
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
          <div class="mail-item-left" @click="handleEdit(mail.id)">
            <span class="mail-receiver">To: {{ mail.receiverNames || mail.receiverIds || '(未填写收件人)' }}</span>
          </div>
          <div class="mail-item-center" @click="handleEdit(mail.id)">
            <span class="mail-subject">{{ mail.subject || '(无主题)' }}</span>
            <span class="mail-preview">{{ mail.body ? mail.body.substring(0, 80) : '' }}</span>
          </div>
          <div class="mail-item-right">
            <el-tag size="small" type="warning" effect="plain">草稿</el-tag>
            <span class="mail-time">{{ formatTime(mail.sendTime) }}</span>
            <div class="mail-actions" @click.stop>
              <el-button
                type="danger"
                size="small"
                text
                :loading="deletingId === mail.id"
                @click="handleDelete(mail.id)"
              >
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
          </div>
        </div>
      </template>
      <el-empty v-else description="草稿箱为空">
        <el-button type="primary" @click="$router.push('/compose')">去写信</el-button>
      </el-empty>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listMails, deleteDraft } from '@/api/mail'
import { formatTime } from '@/utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit } from '@element-plus/icons-vue'

const router = useRouter()
const mails = ref([])
const loading = ref(false)
const deletingId = ref(null)

onMounted(() => refreshMails())

async function refreshMails() {
  loading.value = true
  try {
    const res = await listMails(4)
    mails.value = res.data || []
  } finally {
    loading.value = false
  }
}

function handleEdit(id) {
  router.push({ path: '/compose', query: { draftId: id } })
}

async function handleDelete(id) {
  try {
    await ElMessageBox.confirm(
      '删除后将无法恢复，确定要删除此草稿吗？',
      '确认删除',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    deletingId.value = id
    await deleteDraft(id)
    ElMessage.success('草稿已删除')
    await refreshMails()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    // error handled by interceptor
  } finally {
    deletingId.value = null
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
  min-width: 200px;
  cursor: pointer;
}

.mail-receiver {
  color: #606266;
  font-size: 14px;
}

.mail-item-center {
  flex: 1;
  overflow: hidden;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.mail-subject {
  font-weight: 500;
}

.mail-preview {
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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
  opacity: 0;
  transition: opacity 0.2s;
}

.mail-item:hover .mail-actions {
  opacity: 1;
}
</style>
