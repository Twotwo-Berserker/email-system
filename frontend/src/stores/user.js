import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { login as loginApi, register as registerApi, logout as logoutApi } from '@/api/user'

/**
 * 用户状态管理
 */
export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const userInfo = ref(JSON.parse(localStorage.getItem('userInfo') || 'null'))

  /**
   * 是否管理员。
   * 仅用于前端展示与路由守卫 —— 后端 AdminInterceptor 才是真正的权限边界，
   * 改这里绕不过任何接口。
   */
  const isAdmin = computed(() => userInfo.value?.role === 'ADMIN')

  /** 登录 */
  async function doLogin(email, password) {
    const res = await loginApi({ email, password })
    token.value = res.data.token
    userInfo.value = {
      id: res.data.userId,
      email: res.data.email,
      nickname: res.data.nickname,
      role: res.data.role || 'USER',
      mustChangePassword: res.data.mustChangePassword === true
    }
    localStorage.setItem('token', token.value)
    localStorage.setItem('userInfo', JSON.stringify(userInfo.value))
    return res
  }

  /** 注册 */
  async function doRegister(email, password, nickname) {
    return await registerApi({ email, password, nickname })
  }

  /**
   * 退出登录
   * 先调后端把 token 的 jti 写入吊销名单，再清本地。
   * 接口失败不阻塞退出 —— 本地登录态必须清掉，
   * 否则用户会遇到"点了退出却还在登录"这种更糟的状态。
   */
  async function logout() {
    try {
      await logoutApi()
    } catch (e) {
      // 静默：Token 已过期/网络异常都不该阻止本地登出
    }
    clearLocal()
  }

  /** 仅清本地登录态（不调接口） */
  function clearLocal() {
    token.value = ''
    userInfo.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
  }

  /** 强制改密完成后更新标记，避免路由守卫再次拦截 */
  function markPasswordChanged() {
    if (userInfo.value) {
      userInfo.value.mustChangePassword = false
      localStorage.setItem('userInfo', JSON.stringify(userInfo.value))
    }
  }

  return { token, userInfo, isAdmin, doLogin, doRegister, logout, clearLocal, markPasswordChanged }
})
