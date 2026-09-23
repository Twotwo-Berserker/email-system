import request from './request'

/**
 * 发送邮件
 */
export function sendMail(data) {
  return request.post('/mail/send', data)
}

/**
 * 转发邮件
 */
export function forwardMail(id, data) {
  return request.post(`/mail/forward/${id}`, data)
}

/**
 * 拉取收件箱
 */
export function receiveMails() {
  return request.get('/mail/receive')
}

/**
 * 邮件列表（按类型，带分页）
 * type: 1=收件箱, 2=已发送, 3=垃圾箱, 4=草稿
 */
export function listMails(type = 1, page = 1, pageSize = 20) {
  return request.get('/mail/list', { params: { type, page, pageSize } })
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

// ==================== 智能分析与人工反馈 ====================
// 列表页读的是 Mail 上的 category/isSpam/priority/summary（后端已用 COALESCE
// 覆盖成当前用户的结论）；这里这几个接口只服务详情页的分析面板与反馈区。

/**
 * 分析面板：来源、风险、置信度、判定依据、建议动作、模型与 Prompt 版本
 * silent —— 邮件尚未分析（或该用户没有结果）时后端返回空视图，
 * 不该弹错误框
 */
export function mailAnalysis(id) {
  return request.get(`/mail/detail/${id}/analysis`, { silent: true })
}

/**
 * 重新分析（跳过"内容未变则不重跑"的检查）。
 * 同步执行：单封邮件最坏情况等一次读超时（默认 12 秒）
 */
export function reanalyzeMail(id) {
  return request.post(`/mail/detail/${id}/reanalyze`)
}

/**
 * 提交反馈
 * @param {number} id
 * @param {{feedbackType: 'AGREE'|'DISAGREE', correctedCategory?: string,
 *          correctedSpam?: 0|1, comment?: string}} data
 */
export function submitMailFeedback(id, data) {
  return request.post(`/mail/detail/${id}/feedback`, data)
}

/**
 * 查询当前用户对某封邮件的反馈
 */
export function getMailFeedback(id) {
  return request.get(`/mail/detail/${id}/feedback`, { silent: true })
}

/**
 * 标记已读
 */
export function markAsRead(id) {
  return request.put(`/mail/read/${id}`)
}

/**
 * 标记未读
 */
export function markAsUnread(id) {
  return request.put(`/mail/unread/${id}`)
}

/**
 * 切换已读/未读状态
 */
export function toggleMailRead(id) {
  return request.put(`/mail/toggle-read/${id}`)
}

/**
 * 删除邮件
 */
export function deleteMail(id) {
  return request.delete(`/mail/delete/${id}`)
}

/**
 * 批量删除邮件
 */
export function batchDeleteMail(mailIds) {
  return request.delete('/mail/batch-delete', { data: mailIds })
}

/**
 * 批量永久删除邮件
 */
export function batchPermanentDeleteMail(mailIds) {
  return request.delete('/mail/batch-permanent-delete', { data: mailIds })
}

/**
 * 搜索邮件（带分页）
 */
export function searchMails(keyword, page = 1, pageSize = 20) {
  return request.get('/mail/search', { params: { keyword, page, pageSize } })
}

/**
 * 邮件增量同步
 */
export function syncMails(since) {
  return request.get('/mail/sync', { params: { since } })
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
