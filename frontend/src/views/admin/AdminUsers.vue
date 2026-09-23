<template>
  <div class="admin-users-page">
    <h2>👥 用户管理</h2>
    <p class="page-desc">管理系统账号的角色、启用状态与密码。所有操作均记录操作人。</p>

    <!-- 概览卡片 -->
    <div class="stat-cards">
      <div class="stat-card">
        <div class="stat-value">{{ overview.userTotal ?? '-' }}</div>
        <div class="stat-label">用户总数</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">{{ overview.userEnabled ?? '-' }}</div>
        <div class="stat-label">已启用</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">{{ overview.userDisabled ?? '-' }}</div>
        <div class="stat-label">已禁用</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">{{ overview.adminTotal ?? '-' }}</div>
        <div class="stat-label">管理员</div>
      </div>
    </div>

    <el-alert
      v-if="overview.weakPasswordUsers > 0"
      type="warning"
      show-icon
      :closable="false"
      :title="`有 ${overview.weakPasswordUsers} 个账号仍在使用旧版弱密码哈希`"
      description="这些账号的密码在数据库泄露时更容易被还原。建议逐个重置密码，用户重新登录后会自动升级为 BCrypt 哈希。"
      style="margin-bottom: 16px"
    />

    <!-- 筛选 -->
    <el-form :inline="true" class="filter-form">
      <el-form-item label="关键字">
        <el-input
          v-model="filters.keyword"
          placeholder="邮箱或昵称"
          clearable
          style="width: 200px"
          @keyup.enter="handleSearch"
        />
      </el-form-item>
      <el-form-item label="角色">
        <el-select v-model="filters.role" placeholder="全部" clearable style="width: 120px">
          <el-option label="普通用户" value="USER" />
          <el-option label="管理员" value="ADMIN" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="filters.status" placeholder="全部" clearable style="width: 120px">
          <el-option label="已启用" :value="1" />
          <el-option label="已禁用" :value="0" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 用户表格 -->
    <el-table :data="users" v-loading="loading" border stripe style="width: 100%">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="email" label="邮箱" min-width="200" show-overflow-tooltip />
      <el-table-column prop="nickname" label="昵称" min-width="120" show-overflow-tooltip />
      <el-table-column label="角色" width="110">
        <template #default="{ row }">
          <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'" size="small">
            {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '已启用' : '已禁用' }}
          </el-tag>
          <el-tag v-if="row.mustChangePassword" type="warning" size="small" style="margin-left: 4px">
            待改密
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最后登录" min-width="170">
        <template #default="{ row }">
          <div v-if="row.lastLoginTime">
            <div>{{ formatTime(row.lastLoginTime) }}</div>
            <div class="sub-text">{{ row.lastLoginIp || '-' }}</div>
          </div>
          <span v-else class="sub-text">从未登录</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="290" fixed="right">
        <template #default="{ row }">
          <el-button
            size="small"
            :disabled="isSelf(row)"
            @click="handleToggleRole(row)"
          >
            {{ row.role === 'ADMIN' ? '降为普通' : '设为管理员' }}
          </el-button>
          <el-button
            size="small"
            :type="row.status === 1 ? 'danger' : 'success'"
            :disabled="isSelf(row)"
            @click="handleToggleStatus(row)"
          >
            {{ row.status === 1 ? '禁用' : '启用' }}
          </el-button>
          <el-button size="small" @click="openResetDialog(row)">重置密码</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <span class="sub-text">没有符合条件的用户</span>
      </template>
    </el-table>

    <el-pagination
      v-if="total > 0"
      class="pagination"
      background
      layout="total, sizes, prev, pager, next"
      :total="total"
      :current-page="page"
      :page-size="pageSize"
      :page-sizes="[10, 20, 50, 100]"
      @current-change="handlePageChange"
      @size-change="handleSizeChange"
    />

    <!-- 重置密码对话框 -->
    <el-dialog v-model="resetDialogVisible" title="重置密码" width="440px">
      <p class="dialog-desc">
        为 <b>{{ resetTarget?.email }}</b> 设置一个新密码。该用户下次登录时会被要求立即修改密码。
      </p>
      <el-input
        v-model="resetPasswordValue"
        type="password"
        placeholder="新密码（6-32 位）"
        show-password
        @keyup.enter="confirmReset"
      />
      <template #footer>
        <el-button @click="resetDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="resetting" @click="confirmReset">确定重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useUserStore } from '@/stores/user'
import {
  listUsers,
  updateUserRole,
  updateUserStatus,
  resetUserPassword,
  getOverview
} from '@/api/admin'
import { ElMessage, ElMessageBox } from 'element-plus'

const userStore = useUserStore()

const users = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const overview = ref({})

const filters = reactive({
  keyword: '',
  role: '',
  status: ''
})

const resetDialogVisible = ref(false)
const resetTarget = ref(null)
const resetPasswordValue = ref('')
const resetting = ref(false)

/** 是自己就不允许改角色/禁用 —— 后端也会拒绝，这里只是提前置灰 */
function isSelf(row) {
  return row.id === userStore.userInfo?.id
}

/**
 * 后端 LocalDateTime 序列化为 ISO 字符串（如 2026-09-22T10:30:00），
 * 前端统一显示成 "2026-09-22 10:30:00"
 */
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

async function loadUsers() {
  loading.value = true
  try {
    const res = await listUsers({
      page: page.value,
      pageSize: pageSize.value,
      keyword: filters.keyword || undefined,
      role: filters.role || undefined,
      status: filters.status === '' ? undefined : filters.status
    })
    users.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {
    users.value = []
    total.value = 0
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

function handleSearch() {
  page.value = 1
  loadUsers()
}

function handleReset() {
  filters.keyword = ''
  filters.role = ''
  filters.status = ''
  handleSearch()
}

function handlePageChange(newPage) {
  page.value = newPage
  loadUsers()
}

function handleSizeChange(newSize) {
  pageSize.value = newSize
  page.value = 1
  loadUsers()
}

async function handleToggleRole(row) {
  const toAdmin = row.role !== 'ADMIN'
  try {
    await ElMessageBox.confirm(
      toAdmin
        ? `将 ${row.email} 设为管理员？管理员可以管理所有用户与系统配置。`
        : `将 ${row.email} 降为普通用户？其将失去管理入口。`,
      '修改角色',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await updateUserRole(row.id, toAdmin ? 'ADMIN' : 'USER')
    ElMessage.success('角色已更新')
    await Promise.all([loadUsers(), loadOverview()])
  } catch (e) {
    // 如"不能修改自己的角色""最后一个管理员"等，拦截器已弹出后端消息
  }
}

async function handleToggleStatus(row) {
  const disable = row.status === 1
  try {
    await ElMessageBox.confirm(
      disable
        ? `禁用 ${row.email}？该账号将无法登录，已签发的 Token 在下次请求时也会被拒绝。`
        : `启用 ${row.email}？`,
      disable ? '禁用账号' : '启用账号',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await updateUserStatus(row.id, disable ? 0 : 1)
    ElMessage.success(disable ? '账号已禁用' : '账号已启用')
    await Promise.all([loadUsers(), loadOverview()])
  } catch (e) {
    // 错误提示由拦截器处理
  }
}

function openResetDialog(row) {
  resetTarget.value = row
  resetPasswordValue.value = ''
  resetDialogVisible.value = true
}

async function confirmReset() {
  const pwd = resetPasswordValue.value
  if (!pwd || pwd.length < 6 || pwd.length > 32) {
    ElMessage.warning('密码长度需为 6-32 位')
    return
  }
  resetting.value = true
  try {
    await resetUserPassword(resetTarget.value.id, pwd)
    ElMessage.success('密码已重置，该用户下次登录需修改密码')
    resetDialogVisible.value = false
    await loadUsers()
  } catch (e) {
    // 错误提示由拦截器处理
  } finally {
    resetting.value = false
  }
}

onMounted(() => {
  loadUsers()
  loadOverview()
})
</script>

<style scoped>
.admin-users-page {
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

.filter-form {
  margin-bottom: 8px;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.sub-text {
  color: #909399;
  font-size: 12px;
}

.dialog-desc {
  color: #606266;
  margin-bottom: 12px;
  line-height: 1.6;
}
</style>
