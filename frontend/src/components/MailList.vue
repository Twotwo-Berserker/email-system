<template>
  <div class="mail-list-component">
    <div v-if="mails.length === 0 && !loading" class="empty-state">
      <el-empty :description="emptyText" />
    </div>
    <div v-else class="list-container">
      <div
        v-for="mail in mails"
        :key="mail.id"
        class="mail-row"
        :class="{ 'is-unread': mail.isUnread }"
        @click="$emit('select', mail)"
      >
        <div class="mail-col-check">
          <el-checkbox
            :model-value="isSelected(mail.id)"
            @change="(val) => $emit('toggle-select', mail.id, val)"
            @click.stop
          />
        </div>
        <div class="mail-col-sender" :title="mail.senderEmail">
          {{ mail.senderEmail }}
        </div>
        <div class="mail-col-subject">
          <el-tag
            v-if="mail.priority >= 70"
            type="danger"
            size="small"
            effect="dark"
            style="margin-right:4px"
          >重要</el-tag>
          <el-tag
            v-if="mail.isSpam"
            type="warning"
            size="small"
            effect="plain"
            style="margin-right:4px"
          >可疑</el-tag>
          <span class="subject-text">{{ mail.subject }}</span>
          <span class="summary-text">
            — {{ truncateSummary(mail.summary || mail.body, 50) }}
          </span>
        </div>
        <div class="mail-col-time">
          {{ formatTime(mail.sendTime) }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { formatTime, truncateSummary } from '@/utils'

defineProps({
  mails: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  selectedIds: { type: Array, default: () => [] },
  emptyText: { type: String, default: '暂无邮件' }
})

defineEmits(['select', 'toggle-select'])

function isSelected(id) {
  return false
}
</script>

<style scoped>
.mail-row {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  border-bottom: 1px solid #f0f0f0;
  cursor: pointer;
  gap: 8px;
  transition: background 0.15s;
}

.mail-row:hover {
  background: #f5f7fa;
}

.mail-row.is-unread {
  font-weight: 600;
  background: #fafbfd;
}

.mail-col-check {
  flex-shrink: 0;
}

.mail-col-sender {
  width: 160px;
  flex-shrink: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
}

.mail-col-subject {
  flex: 1;
  overflow: hidden;
  display: flex;
  align-items: center;
}

.subject-text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.summary-text {
  color: #909399;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mail-col-time {
  width: 80px;
  flex-shrink: 0;
  text-align: right;
  color: #909399;
  font-size: 13px;
}
</style>
