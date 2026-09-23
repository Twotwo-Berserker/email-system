<template>
  <div class="detail-page" v-loading="loading">
    <template v-if="mail">
      <!-- 操作栏 -->
      <div class="detail-toolbar">
        <el-button @click="$router.back()">
          <el-icon><ArrowLeft /></el-icon> 返回
        </el-button>
        <el-button @click="handleReply">
          <el-icon><ChatDotRound /></el-icon> 回复
        </el-button>
        <el-button @click="handleForward">
          <el-icon><Share /></el-icon> 转发
        </el-button>
        <el-button v-if="isFromTrash" type="success" :loading="restoring" @click="handleRestore">
          <el-icon><Upload /></el-icon> 恢复
        </el-button>
        <el-button @click="handleToggleRead" :loading="togglingRead">
          <el-icon><Reading /></el-icon> {{ mail.isRead ? '标记未读' : '标记已读' }}
        </el-button>
        <el-button type="danger" @click="handleDelete">
          <el-icon><Delete /></el-icon> 删除
        </el-button>
      </div>

      <!-- 邮件主题 -->
      <h2 class="detail-subject">
        <el-tag v-if="mail.priority >= 70" type="danger" size="small">高优先级</el-tag>
        <el-tag v-if="mail.isSpam" type="warning" size="small" style="margin-left:6px">垃圾邮件</el-tag>
        {{ mail.subject }}
      </h2>

      <!-- 发件人信息 -->
      <div class="detail-meta">
        <div class="meta-row">
          <span class="meta-label">发件人:</span>
          <span class="meta-value">
            <el-tag
              v-if="isExternalMail(mail)"
              size="small"
              effect="plain"
              style="margin-right: 6px"
            >外部</el-tag>{{ mail.senderEmail }}
          </span>
        </div>
        <!-- 外部投递状态：外发是异步的，这是用户唯一能看到投递结果的地方 -->
        <div class="meta-row" v-if="externalStatusText">
          <span class="meta-label">投递状态:</span>
          <el-tag size="small" :type="externalStatusTagType">{{ externalStatusText }}</el-tag>
          <span v-if="mail.externalError" class="meta-value" style="color:#f56c6c;margin-left:8px">
            {{ mail.externalError }}
          </span>
        </div>
        <div class="meta-row">
          <span class="meta-label">时间:</span>
          <span class="meta-value">{{ mail.sendTime }}</span>
        </div>
        <div class="meta-row" v-if="mail.receiverNames || mail.receiverIds">
          <span class="meta-label">收件人:</span>
          <span class="meta-value">{{ mail.receiverNames || mail.receiverIds }}</span>
        </div>
        <div class="meta-row" v-if="mail.ccNames || mail.ccIds">
          <span class="meta-label">抄送:</span>
          <span class="meta-value">{{ mail.ccNames || mail.ccIds }}</span>
        </div>
        <div class="meta-row" v-if="mail.category">
          <span class="meta-label">智能分类:</span>
          <el-tag size="small" type="info">{{ mail.category }}</el-tag>
        </div>
        <div class="meta-row" v-if="mail.summary">
          <span class="meta-label">AI摘要:</span>
          <span class="meta-value" style="color:#909399;font-style:italic">{{ mail.summary }}</span>
        </div>
      </div>

      <!-- 智能分析面板：结论、判定依据、模型与 Prompt 版本，以及人工反馈入口。
           只有详情页调 /analysis —— 列表页读的 category/isSpam/priority/summary
           四个字段已由后端 COALESCE 覆盖，前端零改动。-->
      <div class="analysis-panel" v-if="analysis">
        <div class="analysis-head">
          <h3 class="analysis-title">智能分析</h3>
          <el-tag v-if="analysis.source" size="small" effect="plain"
                  :type="analysis.source === 'LLM' ? 'primary' : 'info'">
            {{ analysis.source === 'LLM' ? 'LLM 判定' : '规则兜底' }}
          </el-tag>
          <el-tag v-else-if="analysis.status" size="small" type="info" effect="plain">
            {{ statusText }}
          </el-tag>
          <span class="analysis-spacer" />
          <el-button text size="small" :loading="reanalyzing" @click="handleReanalyze">
            <el-icon><Refresh /></el-icon> 重新分析
          </el-button>
        </div>

        <!-- 降级/失败原因：让用户知道"这次为什么不是 LLM 判的" -->
        <el-alert v-if="analysis.errorCode" type="warning" :closable="false" show-icon
                  class="analysis-error"
                  :title="errorText" />

        <div class="analysis-grid">
          <div class="analysis-cell">
            <span class="analysis-label">分类</span>
            <el-tag size="small" type="info">{{ analysis.category || '—' }}</el-tag>
          </div>
          <div class="analysis-cell">
            <span class="analysis-label">垃圾邮件</span>
            <span>{{ analysis.isSpam ? '是' : '否' }}</span>
          </div>
          <div class="analysis-cell">
            <span class="analysis-label">风险等级</span>
            <el-tag size="small" :type="riskTagType" effect="dark">{{ analysis.riskLevel || '—' }}</el-tag>
          </div>
          <div class="analysis-cell">
            <span class="analysis-label">优先级</span>
            <span>{{ analysis.priority ?? '—' }}</span>
          </div>
          <div class="analysis-cell">
            <span class="analysis-label">置信度</span>
            <span>{{ confidenceText }}</span>
          </div>
          <div class="analysis-cell">
            <span class="analysis-label">垃圾评分</span>
            <span>{{ analysis.spamScore ?? '—' }}</span>
          </div>
        </div>

        <!-- 用户纠正过时，把机器原判一并显示：否则改完就再也想不起来原来是什么 -->
        <div v-if="hasOverride" class="analysis-override">
          机器原判：分类 {{ analysis.machineCategory || '—' }} ·
          {{ analysis.machineIsSpam ? '是垃圾' : '非垃圾' }}
          <el-button text size="small" @click="handleAgree">撤销纠正（改为认可机器判断）</el-button>
        </div>

        <div v-if="analysis.indicators?.length" class="analysis-block">
          <div class="analysis-label">判定依据</div>
          <ul class="analysis-list">
            <li v-for="(item, i) in analysis.indicators" :key="i">{{ item }}</li>
          </ul>
        </div>

        <div v-if="analysis.actions?.length" class="analysis-block">
          <div class="analysis-label">建议动作</div>
          <ul class="analysis-list">
            <li v-for="(item, i) in analysis.actions" :key="i">{{ item }}</li>
          </ul>
        </div>

        <div class="analysis-foot">
          <span v-if="analysis.modelName">模型 {{ analysis.modelName }}</span>
          <span v-if="analysis.promptVersion">Prompt {{ analysis.promptVersion }}</span>
          <span v-if="analysis.latencyMs != null">耗时 {{ analysis.latencyMs }} ms</span>
          <span v-if="analysis.revision > 0">已重跑 {{ analysis.revision }} 次</span>
          <span v-if="analysis.analyzedAt">分析于 {{ analysis.analyzedAt }}</span>
        </div>

        <!-- 人工反馈：度量用途，不参与自动学习 -->
        <el-divider />
        <div class="feedback-area">
          <div class="feedback-head">
            <span class="analysis-label">这个判断准吗？</span>
            <template v-if="myFeedback">
              <el-tag size="small" :type="myFeedback.feedbackType === 'AGREE' ? 'success' : 'danger'" effect="plain">
                {{ myFeedback.feedbackType === 'AGREE' ? '已认可' : '已纠正' }}
              </el-tag>
              <span class="feedback-time">{{ myFeedback.updateTime || myFeedback.createTime }}</span>
            </template>
          </div>

          <div class="feedback-buttons">
            <el-button size="small" :type="myFeedback?.feedbackType === 'AGREE' ? 'success' : ''"
                       :loading="submitting === 'AGREE'" @click="handleAgree">
              👍 判断准确
            </el-button>
            <el-button size="small" :type="correcting ? 'danger' : ''"
                       @click="correcting = !correcting">
              👎 有偏差
            </el-button>
          </div>

          <div v-if="correcting" class="feedback-form">
            <div class="feedback-row">
              <span class="feedback-label">正确分类</span>
              <el-select v-model="form.category" placeholder="不改" clearable size="small" style="width: 160px">
                <el-option v-for="c in analysis.categories || []" :key="c" :label="c" :value="c" />
              </el-select>
            </div>
            <div class="feedback-row">
              <span class="feedback-label">应为垃圾邮件</span>
              <el-switch v-model="form.isSpam" />
            </div>
            <el-input v-model="form.comment" type="textarea" :rows="2" maxlength="512" show-word-limit
                      placeholder="补充说明（选填）" />
            <div class="feedback-row">
              <el-button size="small" type="primary" :loading="submitting === 'DISAGREE'"
                         @click="handleDisagree">
                提交纠正
              </el-button>
              <el-button size="small" @click="correcting = false">取消</el-button>
            </div>
          </div>
        </div>
      </div>

      <el-divider />

      <!-- 邮件正文：一律纯文本渲染，不用 v-html。
           外部来信的正文在入库前已由 HtmlUtil 转成纯文本，而站内写信是纯文本
           输入框，因此正文不存在合法的 HTML。反过来，若按"含尖括号就当 HTML"
           渲染，外部发件人只要在 text/plain 正文里写一段 <img onerror=...>
           就能在收件人浏览器里执行脚本（存储型 XSS）。-->
      <div class="detail-body">
        <pre class="plain-body">{{ mail.body }}</pre>
      </div>

      <!-- 附件列表 -->
      <div v-if="attachments.length > 0" class="detail-attachments">
        <el-divider />
        <h3>附件 ({{ attachments.length }})</h3>
        <div class="attachment-list">
          <div
            v-for="att in attachments"
            :key="att.id"
            class="attachment-item"
          >
            <el-icon :size="20"><component :is="getFileIcon(att.fileName)" /></el-icon>
            <span class="att-name">{{ att.fileName }}</span>
            <span class="att-size">{{ formatFileSize(att.fileSize) }}</span>
            <el-button type="primary" link size="small" @click="handleDownload(att)">
              下载
            </el-button>
            <el-button link size="small" @click="handlePreview(att)">
              预览
            </el-button>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getFileIcon, isExternalMail, externalStatusLabel } from '@/utils'
import {
  mailDetail, mailAttachments, markAsRead, toggleMailRead, deleteMail, restoreMail,
  mailAnalysis, reanalyzeMail, submitMailFeedback
} from '@/api/mail'
import { downloadAttachment, previewAttachment } from '@/api/attachment'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, ChatDotRound, Upload, Share, Reading, Refresh } from '@element-plus/icons-vue'
import { useMailActions } from '@/composables/useMailActions'
import { useLocalCache } from '@/composables/useLocalCache'
import { useUserStore } from '@/stores/user'
import { useMailStore } from '@/stores/mail'

const { permanentDeleteWithConfirm } = useMailActions()
const { invalidateMailCache } = useLocalCache()
const userStore = useUserStore()

const route = useRoute()
const router = useRouter()
const mail = ref(null)
const attachments = ref([])
const loading = ref(true)
const restoring = ref(false)
const togglingRead = ref(false)

// ==================== 智能分析面板 ====================
const analysis = ref(null)
const reanalyzing = ref(false)

/** 正在提交的反馈类型（用于按钮 loading）；null 表示没有提交在进行 */
const submitting = ref(null)

/** 纠正表单是否展开 */
const correcting = ref(false)

/**
 * 纠正表单。
 * <p>
 * 初始值取<b>机器结论</b>而不是生效值：用户是在"机器说的对不对"这个语境下
 * 做纠正的，预填生效值会让第二次纠正看起来像在改自己的上一次答案。
 * </p>
 * <p>
 * 提交时只发送与机器结论<b>不同</b>的字段（见 buildFeedbackPayload）——
 * 把没动过的项也发上去，等于声称"我纠正了这一项"，会凭空造出一条 override。
 * </p>
 */
const form = ref({ category: '', isSpam: false, comment: '' })

const isFromTrash = computed(() => route.query.from === 'trash')

const myFeedback = computed(() => analysis.value?.feedback || null)

const hasOverride = computed(() =>
  !!analysis.value && (analysis.value.overrideCategory != null || analysis.value.overrideIsSpam != null)
)

const statusText = computed(() => {
  const map = { PENDING: '待分析', RUNNING: '分析中', DONE: '已完成', FAILED: '分析失败' }
  return map[analysis.value?.status] || analysis.value?.status || ''
})

/** 失败/降级原因的中文说明；未知错误码原样显示，便于排查 */
const errorText = computed(() => {
  const map = {
    NO_KEY: '未配置 LLM API Key，本次使用内置规则判定。到「设置」里填入自己的 Key 可获得完整分析。',
    TIMEOUT: 'LLM 调用超时，本次使用内置规则判定。',
    RATE_LIMITED: '已达到调用频率上限，本次使用内置规则判定。',
    CIRCUIT_OPEN: 'LLM 连续失败已暂时熔断，本次使用内置规则判定。',
    PARSE_FAILED: '模型未返回约定的 JSON 结构，本次使用内置规则判定。',
    OVERLOADED: '服务器繁忙，本次使用内置规则判定。',
    ORPHANED: '这次分析中途中断了，可点「重新分析」重试。',
    INTERNAL: '分析过程出错，本次没有结论。可点「重新分析」重试。'
  }
  return map[analysis.value?.errorCode] || `分析未走完（${analysis.value?.errorCode}）`
})

const riskTagType = computed(() => {
  const map = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'success' }
  return map[analysis.value?.riskLevel] || 'info'
})

const confidenceText = computed(() => {
  const value = analysis.value?.confidence
  if (value == null) return '—'
  return `${Math.round(Number(value) * 100)}%`
})

/** 外部投递状态文案；纯站内邮件为 null，不显示该行 */
const externalStatusText = computed(() => externalStatusLabel(mail.value?.externalStatus))

const externalStatusTagType = computed(() => {
  const status = mail.value?.externalStatus
  if (status === 'SENT') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'info'
})

onMounted(async () => {
  const id = route.params.id
  try {
    const [mailRes, attRes] = await Promise.all([
      mailDetail(id),
      mailAttachments(id),
      // 分析面板单独拉，且失败不影响邮件本身 —— 它是附加信息，
      // 不该因为分析服务没配好就让用户看不到邮件正文
      loadAnalysis(id)
    ])
    mail.value = mailRes.data
    attachments.value = attRes.data || []
    await markAsRead(id)
    // 使缓存失效
    if (userStore.userInfo?.id) {
      invalidateMailCache(userStore.userInfo.id)
    }
  } catch (e) {
    ElMessage.error('加载邮件失败')
  } finally {
    loading.value = false
  }
})

/** 拉取分析面板并重置纠正表单；失败时保持 analysis 为 null，面板整体不渲染 */
async function loadAnalysis(id) {
  try {
    const res = await mailAnalysis(id)
    analysis.value = res.data || null
    resetForm()
  } catch (e) {
    analysis.value = null
  }
}

/** 纠正表单预填机器结论（不是生效值，见 form 的注释） */
function resetForm() {
  form.value = {
    category: analysis.value?.machineCategory || '',
    isSpam: analysis.value?.machineIsSpam === 1,
    comment: ''
  }
  correcting.value = false
}

/**
 * 构造反馈负载。
 * <p>
 * 只提交与机器结论不同的项：为空表示"用户没提这一项"，
 * 把没动过的项也发上去会凭空造出一条 override，让"机器原判"永久失真。
 * </p>
 */
function buildFeedbackPayload(type) {
  const payload = { feedbackType: type }
  const machine = analysis.value || {}

  if (form.value.category && form.value.category !== (machine.machineCategory || '')) {
    payload.correctedCategory = form.value.category
  }
  const spamAsNumber = form.value.isSpam ? 1 : 0
  if (spamAsNumber !== (machine.machineIsSpam ?? null)) {
    payload.correctedSpam = spamAsNumber
  }
  if (form.value.comment?.trim()) {
    payload.comment = form.value.comment.trim()
  }
  return payload
}

async function handleAgree() {
  submitting.value = 'AGREE'
  try {
    // 认可会清掉之前的纠正：后端在 AGREE 分支上 clearOverride。
    // 不这么做的话，用户改口说"其实机器是对的"之后，界面还会显示旧纠正
    const res = await submitMailFeedback(mail.value.id, { feedbackType: 'AGREE' })
    analysis.value = res.data || analysis.value
    resetForm()
    ElMessage.success('已记录你的认可')
    afterAnalysisChanged()
  } catch (e) {
    // 错误在拦截器中已提示
  } finally {
    submitting.value = null
  }
}

async function handleDisagree() {
  submitting.value = 'DISAGREE'
  try {
    const res = await submitMailFeedback(mail.value.id, buildFeedbackPayload('DISAGREE'))
    analysis.value = res.data || analysis.value
    resetForm()
    ElMessage.success('已记录你的纠正')
    afterAnalysisChanged()
  } catch (e) {
    // 错误在拦截器中已提示
  } finally {
    submitting.value = null
  }
}

async function handleReanalyze() {
  reanalyzing.value = true
  try {
    const res = await reanalyzeMail(mail.value.id)
    analysis.value = res.data || analysis.value
    resetForm()
    // 重新分析会改掉生效分类/摘要，而这些字段在 Mail 上也有一份（COALESCE 覆盖），
    // 因此邮件本身要重拉，否则页头的分类标签还是旧的
    const mailRes = await mailDetail(mail.value.id)
    mail.value = mailRes.data
    ElMessage.success('已重新分析')
    afterAnalysisChanged()
  } catch (e) {
    // 错误在拦截器中已提示
  } finally {
    reanalyzing.value = false
  }
}

/** 结论变了：列表缓存与列表数据都要跟着更新 */
function afterAnalysisChanged() {
  if (userStore.userInfo?.id) {
    invalidateMailCache(userStore.userInfo.id)
  }
  useMailStore().bumpListVersion()
}

function handleReply() {
  router.push({
    path: '/compose',
    query: {
      to: mail.value.senderEmail,
      subject: `Re: ${mail.value.subject}`
    }
  })
}

function handleForward() {
  router.push({
    path: '/compose',
    query: {
      forwardId: mail.value.id
    }
  })
}

async function handleToggleRead() {
  togglingRead.value = true
  try {
    const res = await toggleMailRead(mail.value.id)
    mail.value.isRead = res.data ? 1 : 0
    ElMessage.success(res.data ? '已标记为已读' : '已标记为未读')
    if (userStore.userInfo?.id) {
      invalidateMailCache(userStore.userInfo.id)
    }
  } catch (e) {
    ElMessage.error('操作失败')
  } finally {
    togglingRead.value = false
  }
}

async function handleRestore() {
  restoring.value = true
  try {
    await restoreMail(mail.value.id)
    ElMessage.success('邮件已恢复到收件箱')
    router.back()
  } catch (e) {
    // error handled by interceptor
  } finally {
    restoring.value = false
  }
}

async function handleDelete() {
  if (isFromTrash.value) {
    const deleted = await permanentDeleteWithConfirm(mail.value.id)
    if (deleted) {
      router.back()
    }
  } else {
    await ElMessageBox.confirm('确定删除该邮件吗？', '提示', { type: 'warning' })
    try {
      await deleteMail(mail.value.id)
      ElMessage.success('已删除')
      router.back()
    } catch (e) {
      // 取消
    }
  }
}

async function handleDownload(att) {
  try {
    await downloadAttachment(att.id, att.fileName)
  } catch (e) {
    ElMessage.error('下载失败')
  }
}

async function handlePreview(att) {
  try {
    await previewAttachment(att.id)
  } catch (e) {
    ElMessage.error('预览失败')
  }
}

function formatFileSize(bytes) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return size.toFixed(1) + ' ' + units[i]
}
</script>

<style scoped>
.detail-page {
  max-width: 900px;
}

.detail-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 20px;
}

.detail-subject {
  font-size: 22px;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.detail-meta {
  background: #f5f7fa;
  padding: 16px;
  border-radius: 8px;
  margin-bottom: 12px;
}

.meta-row {
  margin-bottom: 6px;
  font-size: 14px;
}

.meta-label {
  color: #909399;
  display: inline-block;
  width: 70px;
}

.meta-value {
  color: #303133;
}

.detail-body {
  min-height: 200px;
  padding: 16px;
  line-height: 1.8;
  font-size: 15px;
}

.plain-body {
  white-space: pre-wrap;
  font-family: inherit;
  line-height: 1.8;
}

.detail-attachments {
  margin-top: 16px;
}

.attachment-list {
  margin-top: 12px;
}

.attachment-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 6px;
  margin-bottom: 8px;
}

.att-name {
  flex: 1;
}

.att-size {
  color: #909399;
  font-size: 13px;
}

/* ==================== 智能分析面板 ==================== */

.analysis-panel {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 12px;
  background: #fff;
}

.analysis-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.analysis-title {
  margin: 0;
  font-size: 15px;
}

.analysis-spacer {
  flex: 1;
}

.analysis-error {
  margin-bottom: 12px;
}

.analysis-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px 16px;
  margin-bottom: 12px;
}

.analysis-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.analysis-label {
  color: #909399;
  font-size: 13px;
  white-space: nowrap;
}

.analysis-override {
  font-size: 13px;
  color: #e6a23c;
  background: #fdf6ec;
  border-radius: 4px;
  padding: 6px 10px;
  margin-bottom: 12px;
}

.analysis-block {
  margin-bottom: 12px;
}

.analysis-list {
  margin: 6px 0 0;
  padding-left: 20px;
  font-size: 14px;
  line-height: 1.7;
  color: #303133;
}

.analysis-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 12px;
  color: #909399;
}

/* ==================== 人工反馈 ==================== */

.feedback-area {
  font-size: 14px;
}

.feedback-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.feedback-time {
  color: #c0c4cc;
  font-size: 12px;
}

.feedback-buttons {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.feedback-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 6px;
}

.feedback-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.feedback-label {
  color: #606266;
  font-size: 13px;
  min-width: 84px;
}
</style>
