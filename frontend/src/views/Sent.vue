<template>
  <div class="sent-page">
    <div class="toolbar">
      <h2>📤 已发送</h2>
      <div>
        <el-button @click="refreshMails">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </div>
    </div>

    <div class="mail-list" v-loading="loading">
      <template v-if="mails.length > 0">
        <div
          v-for="mail in mails"
          :key="mail.id"
          class="mail-item"
          @click="$router.push(`/mail/${mail.id}`)"
        >
          <div class="mail-item-left">
            <span class="mail-receiver">To: {{ mail.receiverNames || mail.receiverIds }}</span>
          </div>
          <div class="mail-item-center">
            <span class="mail-subject">{{ mail.subject }}</span>
          </div>
          <div class="mail-item-right">
            <el-tag v-if="mail.category" size="small" type="info" effect="plain">
              {{ mail.category }}
            </el-tag>
            <span class="mail-time">{{ formatTime(mail.sendTime) }}</span>
          </div>
        </div>
      </template>
      <el-empty v-else description="没有已发送的邮件" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { listMails } from '@/api/mail'
import { formatTime } from '@/utils'

const mails = ref([])
const loading = ref(false)

onMounted(() => refreshMails())

async function refreshMails() {
  loading.value = true
  try {
    const res = await listMails(2)
    mails.value = res.data || []
  } finally {
    loading.value = false
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
  cursor: pointer;
  transition: background-color 0.2s;
  gap: 12px;
}

.mail-item:hover {
  background-color: #f5f7fa;
}

.mail-item-left {
  min-width: 180px;
}

.mail-receiver {
  color: #606266;
  font-size: 14px;
}

.mail-item-center {
  flex: 1;
  overflow: hidden;
}

.mail-subject {
  font-weight: 500;
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
</style>
