<template>
  <div class="inbox-page">
    <!-- 工具栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <el-button type="primary" @click="$router.push('/compose')">
          <el-icon><Edit /></el-icon> 写信
        </el-button>
        <el-button @click="refreshMails">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </div>
      <div class="toolbar-right">
        <el-input
          v-model="keyword"
          placeholder="搜索邮件主题或正文…"
          clearable
          :prefix-icon="Search"
          style="width: 280px"
          @keyup.enter="handleSearch"
          @clear="refreshMails"
        />
      </div>
    </div>

    <!-- 邮件列表 -->
    <div class="mail-list" v-loading="loading">
      <template v-if="mails.length > 0">
        <div
          v-for="mail in mails"
          :key="mail.id"
          class="mail-item"
          :class="{ 'mail-unread': !isMailRead(mail) }"
          @click="openMail(mail)"
        >
          <div class="mail-item-left">
            <el-checkbox
              :model-value="selectedIds.includes(mail.id)"
              @change="(val) => toggleSelect(mail.id, val)"
              @click.stop
            />
            <span class="mail-sender">{{ mail.senderEmail }}</span>
          </div>
          <div class="mail-item-center">
            <span class="mail-subject">
              <el-tag v-if="mail.priority >= 70" type="danger" size="small" effect="dark">重要</el-tag>
              <el-tag v-if="mail.isSpam" type="warning" size="small" effect="plain" style="margin-left:4px">可疑</el-tag>
              {{ mail.subject }}
            </span>
            <span class="mail-summary"> — {{ truncateSummary(mail.summary || mail.body, 60) }}</span>
          </div>
          <div class="mail-item-right">
            <span v-if="mail.category" class="mail-category">
              <el-tag size="small" type="info" effect="plain">{{ mail.category }}</el-tag>
            </span>
            <span class="mail-time">{{ formatTime(mail.sendTime) }}</span>
            <el-button text type="danger" size="small" @click.stop="handleDelete(mail)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
        </div>
      </template>
      <el-empty v-else description="收件箱为空" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listMails, deleteMail as apiDelete, searchMails } from '@/api/mail'
import { formatTime, truncateSummary } from '@/utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'

const router = useRouter()
const mails = ref([])
const loading = ref(false)
const keyword = ref('')
const selectedIds = ref([])

onMounted(() => {
  refreshMails()
})

async function refreshMails() {
  loading.value = true
  try {
    const res = await listMails(1)
    mails.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function handleSearch() {
  if (!keyword.value.trim()) {
    refreshMails()
    return
  }
  loading.value = true
  try {
    const res = await searchMails(keyword.value.trim())
    mails.value = res.data || []
  } finally {
    loading.value = false
  }
}

function openMail(mail) {
  router.push(`/mail/${mail.id}`)
}

function isMailRead(mail) {
  // 简化判断：假设 mailStatus 会在详情页加载
  return false
}

function toggleSelect(id, val) {
  if (val) selectedIds.value.push(id)
  else selectedIds.value = selectedIds.value.filter(i => i !== id)
}

async function handleDelete(mail) {
  await ElMessageBox.confirm('确定删除该邮件吗？', '提示', {
    type: 'warning',
    confirmButtonText: '确定',
    cancelButtonText: '取消'
  })
  try {
    await apiDelete(mail.id)
    ElMessage.success('已删除')
    refreshMails()
  } catch (e) {
    // 取消或出错
  }
}
</script>

<style scoped>
.inbox-page {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 16px;
  border-bottom: 1px solid #ebeef5;
  margin-bottom: 12px;
}

.toolbar-left {
  display: flex;
  gap: 8px;
}

.mail-list {
  flex: 1;
  overflow-y: auto;
}

.mail-item {
  display: flex;
  align-items: center;
  padding: 12px 8px;
  border-bottom: 1px solid #f0f0f0;
  cursor: pointer;
  transition: background-color 0.2s;
  gap: 12px;
}

.mail-item:hover {
  background-color: #f5f7fa;
}

.mail-unread {
  font-weight: 600;
  background-color: #fafbfd;
}

.mail-item-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 200px;
}

.mail-sender {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 160px;
}

.mail-item-center {
  flex: 1;
  display: flex;
  align-items: center;
  overflow: hidden;
}

.mail-subject {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  display: flex;
  align-items: center;
  gap: 4px;
}

.mail-summary {
  color: #909399;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-left: 4px;
}

.mail-item-right {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 200px;
  justify-content: flex-end;
}

.mail-time {
  color: #909399;
  font-size: 13px;
  white-space: nowrap;
}
</style>
