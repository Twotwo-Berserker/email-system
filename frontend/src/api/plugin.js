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
  return request.get('/plugin/llm/config')
}

/**
 * 更新LLM大模型配置
 */
export function updateLlmConfig(data) {
  return request.put('/plugin/llm/configure', data)
}

/**
 * 上传并加载动态JAR插件
 */
export function uploadJarPlugin(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/plugin/load', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * 卸载动态插件
 */
export function unloadPlugin(name) {
  return request.delete(`/plugin/unload/${name}`)
}

/**
 * 列出已加载的动态插件
 */
export function listDynamicPlugins() {
  return request.get('/plugin/dynamic/list')
}
