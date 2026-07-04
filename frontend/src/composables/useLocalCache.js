/**
 * 前端 IndexedDB 本地缓存 composable
 * 使用 stale-while-revalidate 策略
 */

const DB_NAME = 'mailSystemCache'
const DB_VERSION = 1
const STORE_NAME = 'cache'

/**
 * 打开/创建 IndexedDB
 */
function openDB() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = (event) => {
      const db = event.target.result
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME)
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

/**
 * 从缓存读取
 */
async function getCache(key) {
  try {
    const db = await openDB()
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readonly')
      const store = tx.objectStore(STORE_NAME)
      const request = store.get(key)
      request.onsuccess = () => {
        const data = request.result
        if (data && data.expiry > Date.now()) {
          resolve(data.value)
        } else {
          resolve(null)
        }
      }
      request.onerror = () => resolve(null)
      tx.oncomplete = () => db.close()
    })
  } catch {
    return null
  }
}

/**
 * 写入缓存
 */
async function setCache(key, value, ttlMs = 300000) {
  try {
    const db = await openDB()
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const store = tx.objectStore(STORE_NAME)
      store.put({ value, expiry: Date.now() + ttlMs }, key)
      tx.oncomplete = () => {
        db.close()
        resolve()
      }
    })
  } catch {
    // 静默失败
  }
}

/**
 * 删除缓存
 */
async function deleteCache(key) {
  try {
    const db = await openDB()
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const store = tx.objectStore(STORE_NAME)
      store.delete(key)
      tx.oncomplete = () => {
        db.close()
        resolve()
      }
    })
  } catch {
    // 静默失败
  }
}

/**
 * 生成缓存键
 */
function cacheKey(userId, type, page) {
  return `mails:${userId}:${type}:${page}`
}

/**
 * 本地缓存 Hook
 * 返回 stale-while-revalidate 模式的 fetch 包装器
 */
export function useLocalCache() {
  /**
   * 带缓存的列表获取
   * @param {string} key - 缓存键
   * @param {Function} fetcher - 实际API调用
   * @param {number} ttlMs - 缓存时间（毫秒），默认5分钟
   * @returns {Promise<{data: any, fromCache: boolean}>}
   */
  async function fetchWithCache(key, fetcher, ttlMs = 300000) {
    // 先尝试缓存
    const cached = await getCache(key)
    if (cached) {
      // 后台刷新
      fetcher().then(fresh => {
        setCache(key, fresh, ttlMs)
      }).catch(() => {})
      return { data: cached, fromCache: true }
    }

    // 缓存未命中，直接请求
    const data = await fetcher()
    // 后台写入缓存
    setCache(key, data, ttlMs).catch(() => {})
    return { data, fromCache: false }
  }

  /**
   * 清除用户的邮件列表缓存
   */
  async function invalidateMailCache(userId) {
    for (let type = 1; type <= 4; type++) {
      for (let page = 1; page <= 3; page++) {
        await deleteCache(cacheKey(userId, type, page))
      }
    }
  }

  return {
    fetchWithCache,
    invalidateMailCache,
    cacheKey
  }
}
