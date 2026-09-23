<template>
  <div class="settings-page">
    <h2>⚙️ 个人设置</h2>
    <p class="settings-desc">配置你自己的大模型 API，用于分析你收到的邮件。</p>

    <el-alert
      v-if="inherited"
      type="info"
      show-icon
      :closable="false"
      title="当前使用系统默认配置"
      description="你还没有单独配置密钥，邮件分析会使用管理员配置的系统默认模型。填写下方内容可覆盖为专属配置。"
      style="margin-bottom: 20px"
    />

    <el-form :model="llmForm" label-width="120px" class="llm-form" v-loading="loading">
      <el-form-item label="启用大模型">
        <el-switch v-model="llmForm.enabled" active-text="启用" inactive-text="禁用" />
        <div class="field-hint">
          关闭后邮件仍会被分析，但改由内置规则完成（结果标记为 RULE）
        </div>
      </el-form-item>

      <el-form-item label="API端点">
        <el-input v-model="llmForm.apiEndpoint" placeholder="https://api.openai.com/v1" />
        <div class="field-hint">OpenAI / DeepSeek 等兼容 Chat Completions 的服务填到 /v1 为止</div>
      </el-form-item>

      <el-form-item label="API密钥">
        <el-input
          v-model="llmForm.apiKey"
          type="password"
          :placeholder="hasStoredKey ? '已保存，留空则保持不变' : 'sk-...'"
          show-password
        />
        <div class="field-hint">
          <template v-if="hasStoredKey">
            已保存的密钥为 <code>{{ llmForm.apiKey }}</code>。
            <b>不改动就保持原样提交</b>（服务端识别掩码后不会覆盖）；要更换请直接输入新密钥。
          </template>
          <template v-else>尚未保存密钥。密钥在服务端加密存储，接口只回传掩码。</template>
        </div>
      </el-form-item>

      <el-form-item label="模型名称">
        <el-input v-model="llmForm.modelName" placeholder="gpt-3.5-turbo" />
      </el-form-item>

      <el-form-item>
        <el-button type="primary" :loading="saving" @click="saveLlmConfig">保存配置</el-button>
        <el-button :loading="reloading" @click="loadLlmConfig">重新加载</el-button>
        <el-button
          v-if="!inherited"
          type="danger"
          plain
          :loading="removing"
          @click="removeLlmConfig"
        >
          恢复系统默认
        </el-button>
      </el-form-item>
    </el-form>

    <el-divider />
    <h2>🔑 账号安全</h2>
    <p class="settings-desc">定期更换密码，避免使用与其他网站相同的密码。</p>
    <el-button @click="$router.push('/change-password')">修改密码</el-button>
  </div>
</template>

<script setup>
import { computed, reactive, onMounted, ref } from 'vue'
import { getMyLlmConfig, updateMyLlmConfig, deleteMyLlmConfig } from '@/api/user'
import { ElMessage, ElMessageBox } from 'element-plus'

const loading = ref(false)
const saving = ref(false)
const reloading = ref(false)
const removing = ref(false)

/** 是否在用系统默认配置（自己没配，或自己配的不可用） */
const inherited = ref(false)

/** 掩码标记，与后端 PluginServiceImpl.MASK_MARKER 保持一致 */
const MASK_MARKER = '****'

const llmForm = reactive({
  apiEndpoint: 'https://api.openai.com/v1',
  apiKey: '',
  modelName: 'gpt-3.5-turbo',
  enabled: false
})

const hasStoredKey = computed(() => llmForm.apiKey.includes(MASK_MARKER))

function applyConfig(data) {
  if (!data) return
  llmForm.apiEndpoint = data.apiEndpoint || 'https://api.openai.com/v1'
  // 服务端回传的就是掩码（如 ****abcd），原样放进输入框：
  // 提交时后端凭 **** 判断"未修改"，所以这里不能清空
  llmForm.apiKey = data.apiKey || ''
  llmForm.modelName = data.modelName || 'gpt-3.5-turbo'
  llmForm.enabled = data.enabled === 1
  inherited.value = data.inheritedFromSystem === true
}

async function loadLlmConfig() {
  loading.value = true
  try {
    const res = await getMyLlmConfig()
    applyConfig(res.data)
  } catch (e) {
    // silent 模式不弹窗：后端不可用时页面其余部分仍可用
  } finally {
    loading.value = false
  }
}

async function reloadLlmConfig() {
  reloading.value = true
  try {
    const res = await getMyLlmConfig()
    applyConfig(res.data)
    ElMessage.success('已重新加载')
  } catch (e) {
    ElMessage.error('加载失败，请检查后端服务')
  } finally {
    reloading.value = false
  }
}

async function saveLlmConfig() {
  saving.value = true
  try {
    await updateMyLlmConfig({
      apiEndpoint: llmForm.apiEndpoint,
      apiKey: llmForm.apiKey,
      modelName: llmForm.modelName,
      enabled: llmForm.enabled
    })
    ElMessage.success('配置已保存')
    // 重新拉取，拿到最新的掩码与继承状态
    await loadLlmConfig()
  } catch (e) {
    // 错误提示由 request 拦截器统一弹出
  } finally {
    saving.value = false
  }
}

async function removeLlmConfig() {
  try {
    await ElMessageBox.confirm(
      '将删除你专属的模型配置，之后邮件分析会使用管理员配置的系统默认模型。确定继续吗？',
      '恢复系统默认',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch (e) {
    return // 用户取消
  }
  removing.value = true
  try {
    await deleteMyLlmConfig()
    ElMessage.success('已恢复为系统默认配置')
    await loadLlmConfig()
  } catch (e) {
    // 错误提示由 request 拦截器统一弹出
  } finally {
    removing.value = false
  }
}

onMounted(loadLlmConfig)
</script>

<style scoped>
.settings-page {
  max-width: 700px;
}

.settings-desc {
  color: #909399;
  margin-bottom: 24px;
}

.llm-form {
  margin-top: 12px;
}

.field-hint {
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
  margin-top: 4px;
}

.field-hint code {
  background: #f5f7fa;
  padding: 1px 4px;
  border-radius: 3px;
}
</style>
