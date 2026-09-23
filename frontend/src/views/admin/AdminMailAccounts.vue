<template>
  <div class="admin-mail-accounts-page">
    <h2>📮 邮箱账户</h2>
    <p class="page-desc">
      用户绑定的外部邮箱。系统按设置的间隔轮询这些账户的收件箱，并用它们对外发信。
    </p>

    <div class="stat-cards">
      <div class="stat-card">
        <div class="stat-value">{{ overview.mailAccountTotal ?? '-' }}</div>
        <div class="stat-label">绑定总数</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">{{ overview.mailAccountEnabled ?? '-' }}</div>
        <div class="stat-label">已启用</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">{{ overview.mailExternal ?? '-' }}</div>
        <div class="stat-label">外部来信</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">{{ failedCount }}</div>
        <div class="stat-label">同步异常</div>
      </div>
    </div>

    <el-alert
      v-if="unavailable"
      type="info"
      show-icon
      :closable="false"
      title="暂无绑定记录"
      description="用户在「个人设置」中绑定邮箱后，这里会显示同步状态与失败原因。"
      style="margin-bottom: 20px"
    />

    <el-table v-else :data="accounts" v-loading="loading" border stripe style="width: 100%">
      <el-table-column prop="ownerEmail" label="所属用户" min-width="160" show-overflow-tooltip />
      <el-table-column label="绑定邮箱" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <div>{{ row.emailAddress }}</div>
          <div class="sub-text">{{ row.displayName || '-' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="服务器" min-width="200">
        <template #default="{ row }">
          <div class="sub-text">
            SMTP {{ row.smtpHost }}:{{ row.smtpPort }}{{ row.smtpSsl ? ' (SSL)' : '' }}
          </div>
          <div class="sub-text">
            IMAP {{ row.imapHost }}:{{ row.imapPort }}{{ row.imapSsl ? ' (SSL)' : '' }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small">
            {{ row.enabled === 1 ? '已启用' : '已停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近同步" min-width="200">
        <template #default="{ row }">
          <div v-if="!row.lastSyncTime" class="sub-text">尚未同步</div>
          <template v-else>
            <div>
              <el-tag :type="syncTagType(row.lastSyncStatus)" size="small">
                {{ syncStatusLabel(row.lastSyncStatus) }}
              </el-tag>
              <span class="sub-text" style="margin-left: 6px">{{ formatTime(row.lastSyncTime) }}</span>
            </div>
            <div v-if="row.lastSyncError" class="error-text" :title="row.lastSyncError">
              {{ row.lastSyncError }}
            </div>
          </template>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button size="small" :loading="syncing === row.id" @click="handleSync(row)">
            立即同步
          </el-button>
          <el-button size="small" type="danger" @click="handleUnbind(row)">解绑</el-button>
        </template>
      </el-table-column>
      <template #empty><span class="sub-text">暂无绑定记录</span></template>
    </el-table>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { listMailAccounts, syncMailAccount, deleteMailAccount, getOverview } from '@/api/admin'
import { ElMessage, ElMessageBox } from 'element-plus'

const accounts = ref([])
const loading = ref(false)
const unavailable = ref(false)
const syncing = ref(null)
const overview = ref({})

const failedCount = computed(
  () => accounts.value.filter(a => a.lastSyncStatus && a.lastSyncStatus !== 'SUCCESS').length
)

const SYNC_LABELS = {
  SUCCESS: '成功',
  FAILED: '失败',
  AUTH_FAILED: '认证失败'
}

function syncStatusLabel(status) {
  return SYNC_LABELS[status] || status || '-'
}

function syncTagType(status) {
  if (status === 'SUCCESS') return 'success'
  if (!status) return 'info'
  return 'danger'
}

function formatTime(value) {
  if (!value) return '-'
  if (Array.isArray(value)) {
    const [y, m, d, h = 0, mi = 0, s = 0] = value
    return `${y}-${pad(m)}-${pad(d)} ${pad(h)}:${pad(mi)}:${pad(s)}`
  }
  return String(value).replace('T', ' ').slice(0, 19)
}

function pad(n) {
  return String(n).padStart(2, '0')
}

async function loadAccounts() {
  loading.value = true
  try {
    // 管理端接口返回的是数组而非分页对象：绑定账户是低频数据，
    // 一次拉全量比让前端维护分页状态简单
    const res = await listMailAccounts()
    accounts.value = res.data || []
    unavailable.value = accounts.value.length === 0
  } catch (e) {
    accounts.value = []
    unavailable.value = true
  } finally {
    loading.value = false
  }
}

async function loadOverview() {
  try {
    const res = await getOverview()
    overview.value = res.data || {}
  } catch (e) {
    overview.value = {}
  }
}

async function handleSync(row) {
  syncing.value = row.id
  try {
    // 同步是同步执行的（后端阻塞到本轮结束），响应里带回本次新增邮件数
    const res = await syncMailAccount(row.id)
    const saved = res.data?.syncedCount
    ElMessage.success(saved ? `同步完成，新增 ${saved} 封邮件` : '同步完成，没有新邮件')
    await Promise.all([loadAccounts(), loadOverview()])
  } catch (e) {
    // 错误提示由拦截器处理
  } finally {
    syncing.value = null
  }
}

async function handleUnbind(row) {
  try {
    await ElMessageBox.confirm(
      `解绑 ${row.emailAddress}？解绑后该账户将不再收信，用户需要重新绑定才能继续使用。已收到的邮件不受影响。`,
      '解绑邮箱',
      { type: 'warning', confirmButtonText: '确定解绑', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await deleteMailAccount(row.id)
    ElMessage.success('已解绑')
    await Promise.all([loadAccounts(), loadOverview()])
  } catch (e) {
    // 错误提示由拦截器处理
  }
}

onMounted(() => {
  loadAccounts()
  loadOverview()
})
</script>

<style scoped>
.admin-mail-accounts-page {
  max-width: 1200px;
}

.page-desc {
  color: #909399;
  margin-bottom: 20px;
}

.stat-cards {
  display: flex;
  gap: 16px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.stat-card {
  flex: 1;
  min-width: 120px;
  padding: 16px 20px;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  text-align: center;
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}

.stat-label {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.sub-text {
  color: #909399;
  font-size: 12px;
}

.error-text {
  color: #f56c6c;
  font-size: 12px;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 260px;
}
</style>
