import request from './request'

/**
 * 上传附件
 */
export function uploadAttachment(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/attachment/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * 下载附件（通过axios带token请求，返回blob）
 */
export async function downloadAttachment(id, fileName) {
  const response = await request.get(`/attachment/download/${id}`, {
    responseType: 'blob'
  })
  // blob响应返回的是完整response对象
  const blob = response.data
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName || 'download'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

/**
 * 预览附件（通过axios带token请求，返回blob URL用于新窗口打开）
 */
export async function previewAttachment(id) {
  const response = await request.get(`/attachment/preview/${id}`, {
    responseType: 'blob'
  })
  const blob = response.data
  const url = window.URL.createObjectURL(blob)
  window.open(url, '_blank')
  // 延迟释放URL（等新窗口加载完毕）
  setTimeout(() => window.URL.revokeObjectURL(url), 60000)
}

/**
 * 获取附件下载URL（已废弃，保留兼容）
 */
export function getDownloadUrl(id) {
  return `/api/attachment/download/${id}`
}

/**
 * 获取附件预览URL（已废弃，保留兼容）
 */
export function getPreviewUrl(id) {
  return `/api/attachment/preview/${id}`
}
