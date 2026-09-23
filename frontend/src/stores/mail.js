import { defineStore } from 'pinia'
import { ref } from 'vue'
import { receiveMails, unreadCount as fetchUnread } from '@/api/mail'

/**
 * 邮件状态管理
 */
export const useMailStore = defineStore('mail', () => {
  const mails = ref([])
  const unreadNum = ref(0)

  /**
   * 列表数据版本号。
   * <p>
   * 智能分析是异步的：邮件先落库并推送 NEW_MAIL，几秒后 LLM 才给出分类与摘要。
   * 这期间用户看到的列表项是"没有分类、没有摘要"的。分析完成后后端推送
   * ANALYSIS_DONE，这里自增版本号，列表页 watch 它重新拉取 ——
   * 让那几秒的空白自己补上，而不必用户手动点刷新。
   * </p>
   * <p>
   * 用版本号而不是事件总线：列表页可能是 Inbox / Sent / Trash 中的任意一个，
   * 各自持有自己的请求参数，"有人通知该刷新了"是它们唯一需要知道的公共信息。
   * </p>
   */
  const listVersion = ref(0)

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

  /** 通知所有列表页重新拉取 */
  function bumpListVersion() {
    listVersion.value += 1
  }

  return { mails, unreadNum, listVersion, fetchInbox, refreshUnread, bumpListVersion }
})
