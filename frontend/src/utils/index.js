/**
 * 工具函数
 */

/** 格式化时间 */
export function formatTime(dateStr) {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  const now = new Date()
  const diff = now - date

  // 今天内的显示时间
  if (diff < 86400000 && date.getDate() === now.getDate()) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  // 昨天的
  const yesterday = new Date(now)
  yesterday.setDate(yesterday.getDate() - 1)
  if (date.getDate() === yesterday.getDate()) {
    return '昨天'
  }
  // 今年内的
  if (date.getFullYear() === now.getFullYear()) {
    return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
  }
  return date.toLocaleDateString('zh-CN')
}

/** 截断摘要 */
export function truncateSummary(text, maxLen = 100) {
  if (!text) return ''
  const plain = text.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim()
  if (plain.length <= maxLen) return plain
  return plain.substring(0, maxLen) + '…'
}

/** 获取附件图标 */
export function getFileIcon(fileName) {
  const ext = fileName?.split('.').pop()?.toLowerCase()
  const iconMap = {
    pdf: 'Document',
    doc: 'Document',
    docx: 'Document',
    xls: 'DataAnalysis',
    xlsx: 'DataAnalysis',
    ppt: 'DataLine',
    pptx: 'DataLine',
    jpg: 'Picture',
    jpeg: 'Picture',
    png: 'Picture',
    gif: 'Picture',
    zip: 'FolderOpened',
    rar: 'FolderOpened',
    '7z': 'FolderOpened',
    txt: 'Tickets',
    mp4: 'VideoCamera',
    mp3: 'Headset'
  }
  return iconMap[ext] || 'Paperclip'
}
