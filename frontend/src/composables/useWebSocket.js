import { ref, onUnmounted } from 'vue'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { useUserStore } from '@/stores/user'
import { useMailStore } from '@/stores/mail'
import { ElNotification } from 'element-plus'

/**
 * WebSocket 实时推送 composable
 * 使用 STOMP over WebSocket 接收新邮件通知
 * 连接失败时自动降级为轮询
 */
export function useWebSocket() {
  const connected = ref(false)
  const fallbackPolling = ref(false)
  let stompClient = null
  let pollTimer = null

  const userStore = useUserStore()
  const mailStore = useMailStore()

  function connect() {
    const userId = userStore.userInfo?.id
    if (!userId) return

    try {
      // 使用 SockJS 作为 WebSocket 降级方案
      const socket = new SockJS('/api/ws')
      stompClient = new Client({
        webSocketFactory: () => socket,
        debug: () => {}, // 生产环境禁用日志
        reconnectDelay: 5000,
        onConnect: () => {
          connected.value = true
          fallbackPolling.value = false
          // 停止轮询
          stopPolling()

          // 订阅用户个人通知频道
          stompClient.subscribe(`/topic/user/${userId}`, (message) => {
            try {
              const data = JSON.parse(message.body)
              handleNotification(data)
            } catch (e) {
              // 忽略解析错误
            }
          })

          // 获取当前未读数
          mailStore.refreshUnread()
        },
        onDisconnect: () => {
          connected.value = false
        },
        onStompError: () => {
          connected.value = false
          // WebSocket 失败时启动轮询降级
          startFallbackPolling()
        }
      })

      stompClient.activate()
    } catch (e) {
      // WebSocket 不可用时直接降级
      startFallbackPolling()
    }
  }

  function handleNotification(data) {
    switch (data.type) {
      case 'NEW_MAIL':
        mailStore.refreshUnread()
        ElNotification({
          title: '新邮件',
          message: `${data.payload?.senderEmail || ''} — ${data.payload?.subject || '(无主题)'}`,
          type: 'info',
          duration: 5000
        })
        break
      case 'MAIL_READ':
        mailStore.refreshUnread()
        break
      default:
        break
    }
  }

  function startFallbackPolling() {
    if (fallbackPolling.value) return
    fallbackPolling.value = true
    // 每30秒轮询一次未读数量
    pollTimer = setInterval(() => {
      mailStore.refreshUnread()
    }, 30000)
    // 立即执行一次
    mailStore.refreshUnread()
  }

  function stopPolling() {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
    fallbackPolling.value = false
  }

  function disconnect() {
    stopPolling()
    if (stompClient && stompClient.active) {
      stompClient.deactivate()
    }
  }

  onUnmounted(() => {
    disconnect()
  })

  return {
    connected,
    fallbackPolling,
    connect,
    disconnect
  }
}
