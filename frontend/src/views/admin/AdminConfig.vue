<template>
  <div class="admin-config-page">
    <h2>🛠️ 系统配置</h2>
    <p class="page-desc">这里的设置对所有用户生效。普通用户可在「个人设置」中覆盖为自己专属的大模型密钥。</p>

    <!-- 系统默认 LLM 配置 -->
    <div class="section-card">
      <h3>🤖 系统默认大模型</h3>
      <p class="section-desc">
        未单独配置密钥的用户会使用这套配置。密钥在服务端加密存储，此处只展示尾号。
      </p>

      <el-form :model="llmForm" label-width="120px" v-loading="llmLoading">
        <el-form-item label="启用大模型">
          <el-switch v-model="llmForm.enabled" active-text="启用" inactive-text="禁用" />
          <div class="field-hint">
            关闭后所有用户的邮件分析都会回落到内置规则（结果标记为 RULE）
          </div>
        </el-form-item>

        <el-form-item label="API端点">
          <el-input v-model="llmForm.apiEndpoint" placeholder="https://api.openai.com/v1" />
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
              已保存的密钥为 <code>{{ llmForm.apiKey }}</code>，直接保存不会覆盖它；
              要更换请整串输入新密钥。
            </template>
            <template v-else>尚未配置密钥，此时所有用户都会走规则分析。</template>
          </div>
        </el-form-item>

        <el-form-item label="模型名称">
          <el-input v-model="llmForm.modelName" placeholder="gpt-3.5-turbo" />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="llmSaving" @click="saveLlmConfig">保存</el-button>
          <el-button :loading="llmLoading" @click="loadLlmConfig">重新加载</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 插件开关 -->
    <div class="section-card">
      <h3>🧩 规则插件</h3>
      <p class="section-desc">
        这些是内置的 Java 规则插件，在大模型不可用（无密钥、超时、熔断）时作为兜底分析。
        大模型正常工作时，分类与垃圾识别由大模型主导。
      </p>

      <el-alert
        v-if="pluginError"
        :title="pluginError"
        type="error"
        show-icon
        :closable="false"
        style="margin-bottom: 12px"
      />

      <div class="plugin-list" v-loading="pluginsLoading">
        <div v-for="plugin in plugins" :key="plugin.pluginName" class="plugin-card">
          <div class="plugin-info">
            <div class="plugin-name">{{ pluginLabels[plugin.pluginName] || plugin.pluginName }}</div>
            <div class="plugin-desc">{{ pluginDescs[plugin.pluginName] || '插件功能描述' }}</div>
          </div>
          <el-switch
            :model-value="plugin.enabled === 1"
            :loading="toggling === plugin.pluginName"
            active-text="启用"
            inactive-text="禁用"
            @change="(val) => handleToggle(plugin.pluginName, val)"
          />
        </div>
        <div v-if="!pluginsLoading && plugins.length === 0" class="sub-text">暂无插件</div>
      </div>
    </div>

    <!-- Prompt 版本管理 -->
    <div class="section-card">
      <div class="section-header">
        <div>
          <h3>📝 分析 Prompt 版本</h3>
          <p class="section-desc">
            决定大模型以什么样的角色、按什么 JSON 契约分析邮件。Prompt 措辞的改动
            对准确率的影响往往比换模型更大，因此这里保留多版本：分析结果会记录
            所用版本号，「反馈统计」可按版本对比准确率。
          </p>
        </div>
        <el-button type="primary" size="small" @click="openCreateDialog">新建版本</el-button>
      </div>

      <el-alert
        v-if="promptLoadingFailed"
        type="info"
        show-icon
        :closable="false"
        title="未读取到数据库中的 Prompt"
        description="此时分析会使用代码内置的兜底 Prompt（版本号为 builtin-v1）。执行 deploy/mysql/migration_v3.sql 可写入默认模板。"
        style="margin-bottom: 12px"
      />

      <el-table :data="prompts" v-loading="promptsLoading" border size="small">
        <el-table-column prop="name" label="模板名" width="150" />
        <el-table-column prop="version" label="版本" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.version }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small">
              {{ row.enabled === 1 ? '启用中' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip />
        <el-table-column label="正文预览" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="sub-text">{{ row.contentPreview || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button text size="small" @click="viewPrompt(row)">查看</el-button>
            <el-button
              v-if="row.enabled !== 1"
              text
              type="primary"
              size="small"
              :loading="acting === row.id"
              @click="handlePromptEnable(row)"
            >启用</el-button>
            <el-button
              v-if="row.enabled !== 1"
              text
              type="danger"
              size="small"
              :loading="acting === row.id"
              @click="handlePromptDelete(row)"
            >删除</el-button>
          </template>
        </el-table-column>
        <template #empty><span class="sub-text">暂无自定义版本，正在使用内置兜底 Prompt</span></template>
      </el-table>

      <p class="field-hint">
        启用某个版本会自动停用同名的其他版本；启用中的版本不能删除，也不会被停用
        （那会让分析静默换用代码内置的那份，而它在界面上看不到）。
      </p>
    </div>

    <!-- 查看全文 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="700px" top="6vh">
      <pre class="prompt-full">{{ dialogContent }}</pre>
    </el-dialog>

    <!-- 新建版本 -->
    <el-dialog v-model="createVisible" title="新建 Prompt 版本" width="700px" top="6vh">
      <el-form :model="createForm" label-width="90px">
        <el-form-item label="模板名">
          <el-input v-model="createForm.name" placeholder="mail_analysis" />
        </el-form-item>
        <el-form-item label="版本号">
          <el-input v-model="createForm.version" placeholder="v2" />
          <div class="field-hint">同一模板名下版本号不可重复</div>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="createForm.description" placeholder="这一版改了什么" />
        </el-form-item>
        <el-form-item label="正文">
          <el-input
            v-model="createForm.content"
            type="textarea"
            :rows="14"
            placeholder="必须以 JSON 契约描述输出结构，并包含 Prompt Injection 防护声明"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  getSystemLlmConfig,
  updateSystemLlmConfig,
  listPlugins,
  togglePlugin,
  listPromptTemplates,
  getPromptTemplate,
  createPromptTemplate,
  setPromptTemplateEnabled,
  deletePromptTemplate
} from '@/api/admin'
import { ElMessage, ElMessageBox } from 'element-plus'

const llmLoading = ref(false)
const llmSaving = ref(false)
const pluginsLoading = ref(false)
const pluginError = ref('')
const toggling = ref(null)

const plugins = ref([])

// ==================== Prompt 版本 ====================

const prompts = ref([])
const promptsLoading = ref(false)
const promptLoadingFailed = ref(false)
const acting = ref(null)

const dialogVisible = ref(false)
const dialogTitle = ref('')
const dialogContent = ref('')

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({
  name: 'mail_analysis',
  version: '',
  description: '',
  content: ''
})

async function loadPrompts() {
  promptLoadingFailed.value = false
  promptsLoading.value = true
  try {
    const res = await listPromptTemplates()
    prompts.value = res.data || []
  } catch (e) {
    prompts.value = []
    promptLoadingFailed.value = true
  } finally {
    promptsLoading.value = false
  }
}

/**
 * 查看全文。
 * <p>
 * 列表接口只返回 200 字预览（Prompt 有几十行，全量返回会撑大列表响应），
 * 因此这里单独取一次详情。
 * </p>
 */
async function viewPrompt(row) {
  try {
    const res = await getPromptTemplate(row.id)
    dialogTitle.value = `${res.data?.name} ${res.data?.version}`
    dialogContent.value = res.data?.content || ''
    dialogVisible.value = true
  } catch (e) {
    // 错误提示由拦截器处理
  }
}

function openCreateDialog() {
  createForm.name = 'mail_analysis'
  createForm.version = ''
  createForm.description = ''
  createForm.content = ''
  createVisible.value = true
}

async function submitCreate() {
  creating.value = true
  try {
    // 新建一律不启用：启用会立刻改变所有走大模型的分析结果，
    // 应当是另一个明确的动作（列表里的「启用」按钮）
    await createPromptTemplate({ ...createForm, enabled: false })
    ElMessage.success('版本已创建，确认内容无误后点「启用」')
    createVisible.value = false
    await loadPrompts()
  } catch (e) {
    // 错误提示由拦截器处理
  } finally {
    creating.value = false
  }
}

async function handlePromptEnable(row) {
  try {
    await ElMessageBox.confirm(
      `启用 ${row.name} ${row.version} 后，所有走大模型的邮件分析都会立即使用这一版。确定吗？`,
      '启用 Prompt 版本',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  acting.value = row.id
  try {
    await setPromptTemplateEnabled(row.id, true)
    ElMessage.success('已启用，同名的其他版本已自动停用')
    await loadPrompts()
  } catch (e) {
    // 错误提示由拦截器处理
  } finally {
    acting.value = null
  }
}

async function handlePromptDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除 ${row.name} ${row.version} 吗？历史分析结果里记录的版本号不会因此消失。`,
      '删除 Prompt 版本',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  acting.value = row.id
  try {
    await deletePromptTemplate(row.id)
    ElMessage.success('已删除')
    await loadPrompts()
  } catch (e) {
    // 错误提示由拦截器处理
  } finally {
    acting.value = null
  }
}

const pluginLabels = {
  spamFilter: '垃圾邮件识别',
  prioritySort: '邮件优先级排序',
  linkDetection: '恶意链接/伪造发件人检测',
  summaryGenerator: '智能摘要生成',
  categoryClassifier: '智能分类'
}

const pluginDescs = {
  spamFilter: '基于关键词与规则的垃圾邮件判定，作为大模型不可用时的兜底',
  prioritySort: '多维度内容评分，自动置顶高优先级邮件',
  linkDetection: 'URL 分析与发件人校验，抽取正文中的可疑链接',
  summaryGenerator: '基于规则提取邮件摘要（大模型可用时以模型摘要为准）',
  categoryClassifier: '按规则归类到工作/个人/财务等类别'
}

const MASK_MARKER = '****'

const llmForm = reactive({
  apiEndpoint: 'https://api.openai.com/v1',
  apiKey: '',
  modelName: 'gpt-3.5-turbo',
  enabled: false
})

const hasStoredKey = computed(() => llmForm.apiKey.includes(MASK_MARKER))

async function loadLlmConfig() {
  llmLoading.value = true
  try {
    const res = await getSystemLlmConfig()
    const data = res.data
    if (data) {
      llmForm.apiEndpoint = data.apiEndpoint || 'https://api.openai.com/v1'
      // 掩码原样回填，提交时后端凭 **** 判断"未修改密钥"
      llmForm.apiKey = data.apiKey || ''
      llmForm.modelName = data.modelName || 'gpt-3.5-turbo'
      llmForm.enabled = data.enabled === 1
    }
  } catch (e) {
    // silent 模式，不弹窗
  } finally {
    llmLoading.value = false
  }
}

async function saveLlmConfig() {
  llmSaving.value = true
  try {
    await updateSystemLlmConfig({
      apiEndpoint: llmForm.apiEndpoint,
      apiKey: llmForm.apiKey,
      modelName: llmForm.modelName,
      enabled: llmForm.enabled
    })
    ElMessage.success('系统默认 LLM 配置已保存')
    await loadLlmConfig()
  } catch (e) {
    // 错误提示由拦截器处理
  } finally {
    llmSaving.value = false
  }
}

async function loadPlugins() {
  pluginError.value = ''
  pluginsLoading.value = true
  try {
    const res = await listPlugins()
    plugins.value = res.data || []
  } catch (e) {
    pluginError.value = '插件列表加载失败'
    plugins.value = []
  } finally {
    pluginsLoading.value = false
  }
}

async function handleToggle(name, enabled) {
  toggling.value = name
  try {
    await togglePlugin(name, enabled)
    ElMessage.success(enabled ? `已启用「${pluginLabels[name] || name}」` : `已禁用「${pluginLabels[name] || name}」`)
    const idx = plugins.value.findIndex(p => p.pluginName === name)
    if (idx > -1) {
      plugins.value[idx].enabled = enabled ? 1 : 0
    }
  } catch (e) {
    // 错误提示由拦截器处理
  } finally {
    toggling.value = null
  }
}

onMounted(() => {
  loadLlmConfig()
  loadPlugins()
  loadPrompts()
})
</script>

<style scoped>
.admin-config-page {
  max-width: 860px;
}

.page-desc {
  color: #909399;
  margin-bottom: 20px;
}

.section-card {
  padding: 20px 24px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  margin-bottom: 20px;
}

.section-card h3 {
  margin: 0 0 6px;
  font-size: 16px;
  color: #303133;
}

.section-desc {
  color: #909399;
  font-size: 13px;
  margin-bottom: 16px;
  line-height: 1.6;
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

.plugin-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.plugin-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
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

.sub-text {
  color: #909399;
  font-size: 13px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.prompt-full {
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.7;
  max-height: 60vh;
  overflow-y: auto;
  margin: 0;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 6px;
}
</style>
