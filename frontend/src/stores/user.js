import { defineStore } from 'pinia'
import { ref } from 'vue'
import { login as loginApi, register as registerApi } from '@/api/user'

/**
 * 用户状态管理
 */
export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const userInfo = ref(JSON.parse(localStorage.getItem('userInfo') || 'null'))

  /** 登录 */
  async function doLogin(email, password) {
    const res = await loginApi({ email, password })
    token.value = res.data.token
    userInfo.value = {
      id: res.data.userId,
      email: res.data.email,
      nickname: res.data.nickname
    }
    localStorage.setItem('token', token.value)
    localStorage.setItem('userInfo', JSON.stringify(userInfo.value))
    return res
  }

  /** 注册 */
  async function doRegister(email, password, nickname) {
    return await registerApi({ email, password, nickname })
  }

  /** 退出登录 */
  function logout() {
    token.value = ''
    userInfo.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
  }

  return { token, userInfo, doLogin, doRegister, logout }
})
