import request from './request'

/**
 * 发送邮件
 */
export function sendMail(data) {
  return request.post('/mail/send', data)
}

/**
 * 拉取收件箱
 */
export function receiveMails() {
  return request.get('/mail/receive')
}

/**
 * 邮件列表（按类型: 1=收件箱, 2=已发送, 3=垃圾箱, 4=草稿）
 */
export function listMails(type = 1) {
  return request.get('/mail/list', { params: { type } })
}

/**
 * 邮件详情
 */
export function mailDetail(id) {
  return request.get(`/mail/detail/${id}`)
}

/**
 * 邮件附件列表
 */
export function mailAttachments(id) {
  return request.get(`/mail/detail/${id}/attachments`)
}

/**
 * 标记已读
 */
export function markAsRead(id) {
  return request.put(`/mail/read/${id}`)
}

/**
 * 删除邮件
 */
export function deleteMail(id) {
  return request.delete(`/mail/delete/${id}`)
}

/**
 * 搜索邮件
 */
export function searchMails(keyword) {
  return request.get('/mail/search', { params: { keyword } })
}

/**
 * 获取未读数量
 */
export function unreadCount() {
  return request.get('/mail/unread-count')
}

/**
 * 从垃圾箱恢复邮件
 */
export function restoreMail(id) {
  return request.put(`/mail/restore/${id}`)
}

/**
 * 永久删除邮件
 */
export function permanentDeleteMail(id) {
  return request.delete(`/mail/permanent/${id}`)
}

/**
 * 清空垃圾箱
 */
export function emptyTrash() {
  return request.put('/mail/trash/empty')
}

/**
 * 保存草稿
 */
export function saveDraft(data) {
  return request.post('/mail/draft', data)
}

/**
 * 更新草稿
 */
export function updateDraft(id, data) {
  return request.put(`/mail/draft/${id}`, data)
}

/**
 * 发送草稿
 */
export function sendDraft(id) {
  return request.put(`/mail/draft/${id}/send`)
}

/**
 * 删除草稿
 */
export function deleteDraft(id) {
  return request.delete(`/mail/draft/${id}`)
}
