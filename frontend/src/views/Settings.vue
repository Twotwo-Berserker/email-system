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

    <!-- LLM 大模型配置 -->
    <el-divider />
    <h2>🤖 LLM 大模型设置</h2>
    <p class="settings-desc">配置大语言模型API以实现更智能的邮件分析（支持OpenAI兼容接口）</p>

    <el-form :model="llmForm" label-width="120px" class="llm-form">
      <el-form-item label="启用大模型">
        <el-switch
          v-model="llmForm.enabled"
          :loading="llmSaving"
          @change="saveLlmConfig"
          active-text="启用"
          inactive-text="禁用"
        />
      </el-form-item>
      <el-form-item label="API端点">
        <el-input v-model="llmForm.apiEndpoint" placeholder="https://api.openai.com/v1" />
      </el-form-item>
      <el-form-item label="API密钥">
        <el-input v-model="llmForm.apiKey" type="password" placeholder="sk-..." show-password />
      </el-form-item>
      <el-form-item label="模型名称">
        <el-input v-model="llmForm.modelName" placeholder="gpt-3.5-turbo" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="llmSaving" @click="saveLlmConfig">保存配置</el-button>
        <el-button @click="loadLlmConfig">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 动态插件管理 -->
    <el-divider />
    <h2>🔌 动态插件管理</h2>
    <p class="settings-desc">上传外部JAR插件，扩展邮件系统功能</p>

    <el-upload
      :auto-upload="false"
      :limit="1"
      accept=".jar"
      :on-change="handleJarUpload"
    >
      <el-button type="primary" plain>
        <el-icon><Upload /></el-icon> 上传JAR插件
      </el-button>
      <template #tip>
        <span class="upload-tip">仅支持实现了PluginInterface接口的JAR文件</span>
      </template>
    </el-upload>

    <div v-if="dynamicPlugins.length > 0" class="dynamic-list">
      <h4>已加载的动态插件 ({{ dynamicPlugins.length }})</h4>
      <div v-for="name in dynamicPlugins" :key="name" class="dynamic-item">
        <span>{{ name }}</span>
        <el-button text type="danger" size="small" @click="handleUnloadPlugin(name)">卸载</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { listPlugins, togglePlugin, getLlmConfig, updateLlmConfig, uploadJarPlugin, unloadPlugin, listDynamicPlugins } from '@/api/plugin'
import { ElMessage } from 'element-plus'

const plugins = ref([])
const loading = ref(false)
const toggling = ref(null)
const llmSaving = ref(false)
const dynamicPlugins = ref([])

const pluginLabels = {
  spamFilter: '垃圾邮件识别',
  prioritySort: '邮件优先级排序',
  linkDetection: '恶意链接/伪造发件人检测',
  summaryGenerator: '智能摘要生成',
  categoryClassifier: '智能分类'
}

const llmForm = reactive({
  apiEndpoint: 'https://api.openai.com/v1',
  apiKey: '',
  modelName: 'gpt-3.5-turbo',
  enabled: false
})

function getPluginLabel(name) {
  return pluginLabels[name] || name
}

onMounted(async () => {
  loading.value = true
  try {
    const [pluginRes] = await Promise.all([
      listPlugins(),
      loadLlmConfig(),
      loadDynamicPlugins()
    ])
    plugins.value = pluginRes.data || []
  } finally {
    loading.value = false
  }
})

async function handleToggle(name, enabled) {
  toggling.value = name
  try {
    await togglePlugin(name, enabled)
    ElMessage.success(enabled ? `已启用「${getPluginLabel(name)}」` : `已禁用「${getPluginLabel(name)}」`)
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

async function loadLlmConfig() {
  try {
    const res = await getLlmConfig()
    const data = res.data
    if (data) {
      llmForm.apiEndpoint = data.apiEndpoint || 'https://api.openai.com/v1'
      llmForm.apiKey = data.apiKey || ''
      llmForm.modelName = data.modelName || 'gpt-3.5-turbo'
      llmForm.enabled = data.enabled === 1
    }
  } catch (e) {
    // 忽略
  }
}

async function saveLlmConfig() {
  llmSaving.value = true
  try {
    await updateLlmConfig({
      apiEndpoint: llmForm.apiEndpoint,
      apiKey: llmForm.apiKey,
      modelName: llmForm.modelName,
      enabled: llmForm.enabled
    })
    ElMessage.success('LLM配置已保存')
  } catch (e) {
    // 错误已处理
  } finally {
    llmSaving.value = false
  }
}

async function handleJarUpload(file) {
  try {
    await uploadJarPlugin(file.raw)
    ElMessage.success('JAR插件加载成功')
    loadDynamicPlugins()
  } catch (e) {
    ElMessage.error('插件加载失败')
  }
}

async function handleUnloadPlugin(name) {
  try {
    await unloadPlugin(name)
    ElMessage.success('插件已卸载')
    loadDynamicPlugins()
  } catch (e) {
    ElMessage.error('卸载失败')
  }
}

async function loadDynamicPlugins() {
  try {
    const res = await listDynamicPlugins()
    dynamicPlugins.value = res.data || []
  } catch (e) {
    // 忽略
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

.llm-form {
  margin-top: 12px;
}

.upload-tip {
  font-size: 12px;
  color: #909399;
  margin-left: 8px;
}

.dynamic-list {
  margin-top: 16px;
}

.dynamic-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 6px;
  margin-bottom: 8px;
}
</style>
