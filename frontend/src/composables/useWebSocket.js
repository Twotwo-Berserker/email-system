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

    const token = localStorage.getItem('token')
    if (!token) {
      // 没有 Token 连上也会被服务端在 CONNECT 帧上拒掉，不如直接降级
      startFallbackPolling()
      return
    }

    try {
      // 使用 SockJS 作为 WebSocket 降级方案
      const socket = new SockJS('/api/ws')
      stompClient = new Client({
        webSocketFactory: () => socket,
        // 浏览器的 WebSocket/SockJS 握手持带不了 Authorization 头，
        // 因此 Token 只能放在 STOMP 的 CONNECT 帧上，由服务端
        // WebSocketAuthInterceptor 校验（顺带校验订阅目标是不是自己）
        connectHeaders: { Authorization: `Bearer ${token}` },
        debug: () => {}, // 生产环境禁用日志
        reconnectDelay: 5000,
        onConnect: () => {
          connected.value = true
          fallbackPolling.value = false
          // 停止轮询
          stopPolling()

          // 订阅用户个人通知频道。服务端只放行 /topic/user/{自己}，
          // 订阅别人的主题会收到 ERROR 帧
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
      case 'ANALYSIS_DONE':
        // 分析是异步的：邮件先到、结论后到。后端在结论落库并驱逐列表缓存后
        // 推送本事件，这里让列表页重新拉取，把那几秒的"无分类/无摘要"补上。
        // 不弹通知 —— 用户刚看到 NEW_MAIL 又收到一条"分析完成"只会觉得吵。
        mailStore.bumpListVersion()
        break
      case 'MAIL_SEND_FAILED':
        // 外部投递是异步的：用户点"发送"时接口已经返回成功（站内投递确实
        // 成功了），外部那一路的结果只能靠推送告知。不提示的话，用户要等到
        // 自己去已发送里翻到失败状态才会发现这封信没发出去
        ElNotification({
          title: '外部投递失败',
          message: data.payload?.message || '邮件未能投递到外部邮箱，请检查邮箱账户配置',
          type: 'error',
          duration: 0
        })
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
