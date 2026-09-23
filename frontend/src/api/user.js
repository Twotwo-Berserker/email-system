import request from './request'

/**
 * 用户注册
 */
export function register(data) {
  return request.post('/user/register', data)
}

/**
 * 用户登录
 * 响应含 role 与 mustChangePassword，供路由守卫与强制改密判断使用
 */
export function login(data) {
  return request.post('/user/login', data)
}

/**
 * 退出登录
 * 后端会把当前 token 的 jti 写入吊销名单，使其立即失效
 * silent: true —— 退出时即便接口失败也要继续清本地登录态，不该弹错误框
 */
export function logout() {
  return request.post('/user/logout', null, { silent: true })
}

/**
 * 修改自己的密码
 * @param {{oldPassword: string, newPassword: string}} data
 */
export function changePassword(data) {
  return request.put('/user/change-password', data)
}

/**
 * 获取自己的 LLM 配置（apiKey 为掩码）
 */
export function getMyLlmConfig() {
  return request.get('/user/llm-config', { silent: true })
}

/**
 * 保存自己的 LLM 配置
 * apiKey 传空或掩码串表示"不修改密钥"
 */
export function updateMyLlmConfig(data) {
  return request.put('/user/llm-config', data)
}

/**
 * 删除自己的 LLM 配置（回落到系统默认）
 */
export function deleteMyLlmConfig() {
  return request.delete('/user/llm-config')
}
