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
        <el-button
          v-if="selectedIds.length > 0"
          type="danger"
          @click="handleBatchDelete"
        >
          <el-icon><Delete /></el-icon> 批量删除 ({{ selectedIds.length }})
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
            <!-- 这里只表示"读没读过"，不再兼任切换按钮：一个位置只做一件事。
                 切换动作在右侧，是一个写着字的按钮 —— 原先那个只有图标、
                 要靠悬停提示才能猜出用途的按钮，等于没有 -->
            <span class="unread-dot" :class="{ 'is-unread': !isMailRead(mail) }" />
            <span class="mail-sender">
              <el-tag
                v-if="isExternal(mail)"
                size="small"
                effect="plain"
                title="来自外部邮箱，通过你绑定的邮箱账户收取"
                style="margin-right: 6px"
              >外部</el-tag>{{ mail.senderEmail }}
            </span>
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
            <!-- 按钮写的是"将要发生的动作"，不是当前状态：未读的邮件显示
                 「标为已读」。读与未读都要能在列表里直接改，不必点进详情 -->
            <el-button text size="small" @click.stop="handleToggleRead(mail)">
              {{ isMailRead(mail) ? '标为未读' : '标为已读' }}
            </el-button>
            <el-button text type="danger" size="small" @click.stop="handleDelete(mail)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
        </div>
      </template>
      <el-empty v-else description="收件箱为空" />
    </div>

    <!-- 分页 -->
    <div class="pagination-wrapper" v-if="total > pageSize">
      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="refreshMails"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { listMails, deleteMail as apiDelete, searchMails, batchDeleteMail, toggleMailRead } from '@/api/mail'
import { formatTime, truncateSummary, isExternalMail as isExternal } from '@/utils'
import { useMailStore } from '@/stores/mail'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'

const router = useRouter()
const mailStore = useMailStore()
const mails = ref([])
const loading = ref(false)
const keyword = ref('')
const selectedIds = ref([])
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

onMounted(() => {
  refreshMails()
})

// 智能分析是异步的：邮件先到、分类与摘要后到。分析完成后重新拉取，
// 让列表项上那几秒的空白自己补上。搜索态下也刷新 —— 结果集里同样有分类要更新
watch(() => mailStore.listVersion, refreshMails)

async function refreshMails() {
  loading.value = true
  try {
    if (keyword.value.trim()) {
      const res = await searchMails(keyword.value.trim(), currentPage.value, pageSize.value)
      const data = res.data
      mails.value = data.records || data.data || []
      total.value = data.total || 0
    } else {
      const res = await listMails(1, currentPage.value, pageSize.value)
      const data = res.data
      mails.value = data.records || data.data || []
      total.value = data.total || 0
    }
  } finally {
    loading.value = false
  }
}

async function handleSearch() {
  currentPage.value = 1
  loading.value = true
  try {
    if (!keyword.value.trim()) {
      const res = await listMails(1, 1, pageSize.value)
      const data = res.data
      mails.value = data.records || data.data || []
      total.value = data.total || 0
      return
    }
    const res = await searchMails(keyword.value.trim(), 1, pageSize.value)
    const data = res.data
    mails.value = data.records || data.data || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

function openMail(mail) {
  router.push(`/mail/${mail.id}`)
}

function isMailRead(mail) {
  return mail.isRead === 1
}

async function handleToggleRead(mail) {
  try {
    const res = await toggleMailRead(mail.id)
    const isRead = res.data
    mail.isRead = isRead ? 1 : 0
    ElMessage.success(isRead ? '已标记为已读' : '已标记为未读')
  } catch (e) {
    // 错误在拦截器中已处理
  }
}

function toggleSelect(id, val) {
  if (val) selectedIds.value.push(id)
  else selectedIds.value = selectedIds.value.filter(i => i !== id)
}

async function handleBatchDelete() {
  await ElMessageBox.confirm(
    `确定批量删除选中的 ${selectedIds.value.length} 封邮件吗？`,
    '批量删除',
    { type: 'warning' }
  )
  try {
    await batchDeleteMail(selectedIds.value)
    ElMessage.success('已批量删除')
    selectedIds.value = []
    refreshMails()
  } catch (e) {
    // 取消或出错
  }
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

/* 未读圆点：占位式指示器，已读时只留空位不画东西，
   免得每行都有个灰点在喊"这里本来有个标记" */
.unread-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: transparent;
}

.unread-dot.is-unread {
  background: #409eff;
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

.pagination-wrapper {
  display: flex;
  justify-content: center;
  padding: 16px 0;
}
</style>
