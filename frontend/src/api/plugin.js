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
