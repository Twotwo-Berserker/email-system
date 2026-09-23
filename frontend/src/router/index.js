import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/Register.vue'),
    meta: { title: '注册' }
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        name: 'Inbox',
        component: () => import('@/views/Inbox.vue'),
        meta: { title: '收件箱' }
      },
      {
        path: 'compose',
        name: 'Compose',
        component: () => import('@/views/Compose.vue'),
        meta: { title: '写信' }
      },
      {
        path: 'mail/:id',
        name: 'MailDetail',
        component: () => import('@/views/MailDetail.vue'),
        meta: { title: '邮件详情' }
      },
      {
        path: 'sent',
        name: 'Sent',
        component: () => import('@/views/Sent.vue'),
        meta: { title: '已发送' }
      },
      {
        path: 'drafts',
        name: 'Drafts',
        component: () => import('@/views/Drafts.vue'),
        meta: { title: '草稿箱' }
      },
      {
        path: 'trash',
        name: 'Trash',
        component: () => import('@/views/Trash.vue'),
        meta: { title: '垃圾箱' }
      },
      {
        path: 'settings',
        name: 'Settings',
        component: () => import('@/views/Settings.vue'),
        meta: { title: '个人设置' }
      },
      {
        path: 'mail-accounts',
        name: 'MailAccounts',
        component: () => import('@/views/MailAccounts.vue'),
        meta: { title: '邮箱账户' }
      },
      {
        path: 'change-password',
        name: 'ChangePassword',
        component: () => import('@/views/ChangePassword.vue'),
        meta: { title: '修改密码' }
      },

      // ==================== 管理端 ====================
      // requiresAdmin 只是体验优化：让普通用户看不到管理页面。
      // 后端 AdminInterceptor 会独立校验 /admin/**，改前端绕不过去。
      {
        path: 'admin/users',
        name: 'AdminUsers',
        component: () => import('@/views/admin/AdminUsers.vue'),
        meta: { title: '用户管理', requiresAdmin: true }
      },
      {
        path: 'admin/feedback',
        name: 'AdminFeedbackStats',
        component: () => import('@/views/admin/AdminFeedbackStats.vue'),
        meta: { title: '反馈统计', requiresAdmin: true }
      },
      {
        path: 'admin/mail-accounts',
        name: 'AdminMailAccounts',
        component: () => import('@/views/admin/AdminMailAccounts.vue'),
        meta: { title: '邮箱账户', requiresAdmin: true }
      },
      {
        path: 'admin/config',
        name: 'AdminConfig',
        component: () => import('@/views/admin/AdminConfig.vue'),
        meta: { title: '系统配置', requiresAdmin: true }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/** 读取本地登录态中的用户信息（守卫在 setup 之外执行，不能用 store 的响应式对象） */
function readUserInfo() {
  try {
    return JSON.parse(localStorage.getItem('userInfo') || 'null')
  } catch (e) {
    return null
  }
}

// 路由守卫 — 登录校验 + 管理员校验 + 强制改密
router.beforeEach((to, from, next) => {
  document.title = to.meta.title ? `${to.meta.title} - 邮件系统` : '邮件系统'

  const token = localStorage.getItem('token')

  if (to.matched.some(record => record.meta.requiresAuth)) {
    if (!token) {
      next({ name: 'Login', query: { redirect: to.fullPath } })
      return
    }

    const userInfo = readUserInfo()

    // 管理员重置密码 / 首次登录后必须先改密，否则拦回改密页。
    // 放在这里而不是只在登录成功时跳转 —— 否则用户直接输 URL 就能绕过。
    if (userInfo?.mustChangePassword && to.name !== 'ChangePassword') {
      next({ name: 'ChangePassword' })
      return
    }

    if (to.matched.some(record => record.meta.requiresAdmin) && userInfo?.role !== 'ADMIN') {
      next({ name: 'Inbox' })
      return
    }
  }

  // 已登录用户访问登录/注册页 → 跳转首页
  if ((to.name === 'Login' || to.name === 'Register') && token) {
    const userInfo = readUserInfo()
    // 待改密的用户不要被送去首页（会被守卫再弹回改密页，形成一次无谓跳转）
    next(userInfo?.mustChangePassword ? { name: 'ChangePassword' } : { name: 'Inbox' })
    return
  }

  next()
})

export default router
