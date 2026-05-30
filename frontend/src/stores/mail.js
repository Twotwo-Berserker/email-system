import { defineStore } from 'pinia'
import { ref } from 'vue'
import { receiveMails, unreadCount as fetchUnread } from '@/api/mail'

/**
 * 邮件状态管理
 */
export const useMailStore = defineStore('mail', () => {
  const mails = ref([])
  const unreadNum = ref(0)

  /** 拉取收件箱 */
  async function fetchInbox() {
    const res = await receiveMails()
    mails.value = res.data || []
    return mails.value
  }

  /** 刷新未读数量 */
  async function refreshUnread() {
    const res = await fetchUnread()
    unreadNum.value = res.data || 0
    return unreadNum.value
  }

  return { mails, unreadNum, fetchInbox, refreshUnread }
})
