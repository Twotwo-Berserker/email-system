<template>
  <el-container class="main-layout">
    <!-- 顶部导航 -->
    <el-header class="header">
      <div class="header-left">
        <h1 class="logo" @click="$router.push('/')">📧 邮件系统</h1>
      </div>
      <div class="header-right">
        <el-badge :value="mailStore.unreadNum" :hidden="mailStore.unreadNum === 0" class="unread-badge">
          <el-button type="primary" link @click="$router.push('/')">
            <el-icon><Message /></el-icon> 收件箱
          </el-button>
        </el-badge>
        <el-dropdown trigger="click">
          <span class="user-info">
            <el-avatar :size="32" :icon="UserFilled" />
            <span class="nickname">{{ userStore.userInfo?.nickname || userStore.userInfo?.email }}</span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="$router.push('/settings')">
                <el-icon><Setting /></el-icon> 设置
              </el-dropdown-item>
              <el-dropdown-item divided @click="handleLogout">
                <el-icon><SwitchButton /></el-icon> 退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>

    <el-container>
      <!-- 侧边栏 -->
      <el-aside width="200px" class="sidebar">
        <el-menu
          :default-active="activeMenu"
          :router="true"
          class="sidebar-menu"
        >
          <el-menu-item index="/">
            <el-icon><Message /></el-icon>
            <span>收件箱</span>
          </el-menu-item>
          <el-menu-item index="/sent">
            <el-icon><Promotion /></el-icon>
            <span>已发送</span>
          </el-menu-item>
          <el-menu-item index="/drafts">
            <el-icon><Edit /></el-icon>
            <span>草稿箱</span>
          </el-menu-item>
          <el-menu-item index="/trash">
            <el-icon><Delete /></el-icon>
            <span>垃圾箱</span>
          </el-menu-item>
          <el-divider />
          <el-menu-item index="/settings">
            <el-icon><Setting /></el-icon>
            <span>插件设置</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <!-- 主内容区 -->
      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useMailStore } from '@/stores/mail'
import { useWebSocket } from '@/composables/useWebSocket'
import { UserFilled } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const mailStore = useMailStore()

const activeMenu = computed(() => route.path)

const { connect: connectWebSocket } = useWebSocket()

onMounted(() => {
  // 使用 WebSocket 实时推送（自动降级为轮询）
  connectWebSocket()
})

function handleLogout() {
  userStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.main-layout {
  height: 100vh;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #409eff;
  color: #fff;
  padding: 0 20px;
  height: 56px;
}

.header-left .logo {
  font-size: 20px;
  cursor: pointer;
  user-select: none;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.header-right .el-button--primary.link {
  color: #fff;
}

.unread-badge {
  margin-right: 8px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: #fff;
}

.user-info .nickname {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar {
  background-color: #f5f7fa;
  border-right: 1px solid #e4e7ed;
}

.sidebar-menu {
  border-right: none;
  height: 100%;
}

.main-content {
  background-color: #fff;
  padding: 20px;
  overflow-y: auto;
}
</style>
