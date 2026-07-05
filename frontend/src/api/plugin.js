import request from './request'

/**
 * 获取插件列表
 */
export function listPlugins() {
  return request.get('/plugin/list')
}

/**
 * 启用/禁用插件
 */
export function togglePlugin(pluginName, enabled) {
  return request.put('/plugin/enable', { pluginName, enabled })
}

/**
 * 获取LLM大模型配置
 */
export function getLlmConfig() {
  return request.get('/plugin/llm/config', { silent: true })
}

/**
 * 更新LLM大模型配置
 */
export function updateLlmConfig(data) {
  return request.put('/plugin/llm/configure', data)
}
