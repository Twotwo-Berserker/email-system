import request from './request'

/**
 * 管理端接口 —— 全部走 /admin/*
 * <p>
 * 后端的 AdminInterceptor 对整个 /admin/** 做管理员校验，
 * 前端的 requiresAdmin 路由守卫只是体验优化（避免普通用户看到空白页），
 * 真正的权限边界在后端。
 * </p>
 */

// ==================== 用户管理 ====================

/**
 * 用户列表（分页 + 筛选）
 * @param {{page?: number, pageSize?: number, keyword?: string, role?: string, status?: number}} params
 */
export function listUsers(params) {
  return request.get('/admin/users', { params })
}

/**
 * 修改用户角色
 * @param {number} id
 * @param {'USER'|'ADMIN'} role
 */
export function updateUserRole(id, role) {
  return request.put(`/admin/users/${id}/role`, { role })
}

/**
 * 启用/禁用用户
 * @param {number} id
 * @param {0|1} status
 */
export function updateUserStatus(id, status) {
  return request.put(`/admin/users/${id}/status`, { status })
}

/**
 * 重置用户密码（无需旧密码，但会强制其下次登录改密）
 * @param {number} id
 * @param {string} newPassword
 */
export function resetUserPassword(id, newPassword) {
  return request.put(`/admin/users/${id}/password`, { newPassword })
}

// ==================== 概览 ====================

/**
 * 概览计数
 */
export function getOverview() {
  return request.get('/admin/stats/overview')
}

/**
 * 智能分析的状态分布（PENDING/RUNNING/DONE/FAILED、重跑次数、孤儿行）
 */
export function getAnalysisStats() {
  return request.get('/admin/stats/analysis', { silent: true })
}

/**
 * 近 N 天的 LLM 调用指标：调用数、成功率、降级数、平均延迟、
 * token 消耗、按用户分布
 * @param {number} days 回溯天数，默认 7
 */
export function getLlmStats(days = 7) {
  return request.get('/admin/stats/llm', { params: { days }, silent: true })
}

// ==================== 系统配置 ====================

/**
 * 系统默认 LLM 配置（apiKey 为掩码）
 */
export function getSystemLlmConfig() {
  return request.get('/admin/config/llm', { silent: true })
}

/**
 * 更新系统默认 LLM 配置
 * apiKey 传空或掩码串表示"不修改密钥"
 */
export function updateSystemLlmConfig(data) {
  return request.put('/admin/config/llm', data)
}

/**
 * 插件开关列表
 */
export function listPlugins() {
  return request.get('/admin/config/plugins')
}

/**
 * 启用/禁用插件
 */
export function togglePlugin(name, enabled) {
  return request.put(`/admin/config/plugins/${name}`, { enabled })
}

// ==================== 反馈统计 ====================
// 数据来自用户端「人工反馈回流」，汇总口径见 FeedbackService

/**
 * 反馈汇总统计（准确率、按分类/来源分组的采纳率）
 * silent —— 分析管线尚未产生数据时不应弹错误框，页面自行展示空状态
 */
export function getFeedbackStats() {
  return request.get('/admin/feedback/stats', { silent: true })
}

/**
 * 反馈明细（分页），可下钻到具体邮件
 * @param {{page?: number, pageSize?: number, feedbackType?: string}} params
 */
export function listFeedback(params) {
  return request.get('/admin/feedback/list', { params, silent: true })
}

// ==================== 邮箱账户与同步监控 ====================
// 对应每个用户绑定的外部邮箱（SMTP 发信 / IMAP 收信）

/**
 * 所有用户的邮箱账户及最近一次同步状态
 */
export function listMailAccounts(params) {
  return request.get('/admin/mail-accounts', { params, silent: true })
}

/**
 * 手动触发一次同步
 */
export function syncMailAccount(id) {
  return request.post(`/admin/mail-accounts/${id}/sync`)
}

/**
 * 解绑邮箱账户
 */
export function deleteMailAccount(id) {
  return request.delete(`/admin/mail-accounts/${id}`)
}

// ==================== Prompt 模板 ====================

/**
 * Prompt 模板列表（含版本号与启用状态）
 */
export function listPromptTemplates() {
  return request.get('/admin/config/prompts', { silent: true })
}

/**
 * 某个 Prompt 版本的完整正文（列表只给 200 字预览）
 */
export function getPromptTemplate(id) {
  return request.get(`/admin/config/prompts/${id}`)
}

/**
 * 新建 Prompt 版本。
 * 默认不启用 —— 启用会立刻改变所有走大模型的分析结果，
 * 应当是另一个明确的动作（setPromptTemplateEnabled）
 * @param {{name: string, version: string, content: string,
 *          description?: string, enabled?: boolean}} data
 */
export function createPromptTemplate(data) {
  return request.post('/admin/config/prompts', data)
}

/**
 * 启用/停用某个 Prompt 版本（启用会停用同名的其他版本）
 */
export function setPromptTemplateEnabled(id, enabled) {
  return request.put(`/admin/config/prompts/${id}/enable`, { enabled })
}

/**
 * 删除某个 Prompt 版本（启用中的不可删）
 */
export function deletePromptTemplate(id) {
  return request.delete(`/admin/config/prompts/${id}`)
}
