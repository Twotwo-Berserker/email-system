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
        meta: { title: '设置' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫 — 登录校验
router.beforeEach((to, from, next) => {
  document.title = to.meta.title ? `${to.meta.title} - 邮件系统` : '邮件系统'

  if (to.matched.some(record => record.meta.requiresAuth)) {
    const token = localStorage.getItem('token')
    if (!token) {
      next({ name: 'Login', query: { redirect: to.fullPath } })
      return
    }
  }

  // 已登录用户访问登录/注册页 → 跳转首页
  if ((to.name === 'Login' || to.name === 'Register') && localStorage.getItem('token')) {
    next({ name: 'Inbox' })
    return
  }

  next()
})

export default router
