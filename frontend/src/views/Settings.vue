<template>
  <div class="settings-page">
    <h2>⚙️ 智能插件设置</h2>
    <p class="settings-desc">启用或禁用邮件系统的AI智能插件功能</p>

    <div class="plugin-list" v-loading="loading">
      <div v-for="plugin in plugins" :key="plugin.pluginName" class="plugin-card">
        <div class="plugin-info">
          <div class="plugin-name">{{ getPluginLabel(plugin.pluginName) }}</div>
          <div class="plugin-desc">{{ plugin.description }}</div>
        </div>
        <div class="plugin-switch">
          <el-switch
            :model-value="plugin.enabled === 1"
            :loading="toggling === plugin.pluginName"
            @change="(val) => handleToggle(plugin.pluginName, val)"
            active-text="启用"
            inactive-text="禁用"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { listPlugins, togglePlugin } from '@/api/plugin'
import { ElMessage } from 'element-plus'

const plugins = ref([])
const loading = ref(false)
const toggling = ref(null)

const pluginLabels = {
  spamFilter: '垃圾邮件识别',
  prioritySort: '邮件优先级排序',
  linkDetection: '恶意链接/伪造发件人检测',
  summaryGenerator: '智能摘要生成',
  categoryClassifier: '智能分类'
}

function getPluginLabel(name) {
  return pluginLabels[name] || name
}

onMounted(async () => {
  loading.value = true
  try {
    const res = await listPlugins()
    plugins.value = res.data || []
  } finally {
    loading.value = false
  }
})

async function handleToggle(name, enabled) {
  toggling.value = name
  try {
    await togglePlugin(name, enabled)
    ElMessage.success(enabled ? `已启用「${getPluginLabel(name)}」` : `已禁用「${getPluginLabel(name)}」`)
    // 更新本地状态
    const idx = plugins.value.findIndex(p => p.pluginName === name)
    if (idx > -1) {
      plugins.value[idx].enabled = enabled ? 1 : 0
    }
  } catch (e) {
    // 错误已处理
  } finally {
    toggling.value = null
  }
}
</script>

<style scoped>
.settings-page {
  max-width: 700px;
}

.settings-desc {
  color: #909399;
  margin-bottom: 24px;
}

.plugin-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.plugin-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  background: #f5f7fa;
  border-radius: 8px;
  border: 1px solid #e4e7ed;
}

.plugin-name {
  font-weight: 600;
  font-size: 15px;
  margin-bottom: 4px;
}

.plugin-desc {
  color: #909399;
  font-size: 13px;
}
</style>
