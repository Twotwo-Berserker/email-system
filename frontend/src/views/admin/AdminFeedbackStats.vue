<template>
  <div class="admin-feedback-page">
    <h2>📊 反馈统计</h2>
    <p class="page-desc">
      汇总用户对自动分析结果的认可与纠正。准确率 = 认可数 /（认可数 + 纠正数）。
    </p>

    <el-alert
      v-if="unavailable"
      type="info"
      show-icon
      :closable="false"
      title="暂无可统计的反馈数据"
      description="当用户对邮件分析结果提交「认可」或「纠正」后，这里会显示准确率与分歧样本。"
      style="margin-bottom: 20px"
    />

    <template v-else>
      <!-- 总览 -->
      <div class="stat-cards">
        <div class="stat-card">
          <div class="stat-value">{{ overview.total || 0 }}</div>
          <div class="stat-label">反馈总数</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ overview.agree || 0 }}</div>
          <div class="stat-label">认可</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ overview.disagree || 0 }}</div>
          <div class="stat-label">纠正</div>
        </div>
        <div class="stat-card highlight">
          <div class="stat-value">{{ accuracyText(overview.accuracy) }}</div>
          <div class="stat-label">整体准确率</div>
        </div>
      </div>

      <!-- 分组采纳率 -->
      <div class="group-row">
        <div class="section-card">
          <h3>按分析来源</h3>
          <p class="section-desc">对比大模型（LLM）与规则兜底（RULE）的采纳情况。</p>
          <el-table :data="stats.bySource || []" border size="small">
            <el-table-column label="来源" width="120">
              <template #default="{ row }">
                <el-tag :type="row.source === 'LLM' ? 'primary' : 'info'" size="small">
                  {{ row.source === 'LLM' ? '大模型' : '规则兜底' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="total" label="反馈数" width="90" />
            <el-table-column prop="agree" label="认可" width="80" />
            <el-table-column label="准确率">
              <template #default="{ row }">{{ accuracyText(row.accuracy) }}</template>
            </el-table-column>
            <template #empty><span class="sub-text">暂无数据</span></template>
          </el-table>
        </div>

        <div class="section-card">
          <h3>按邮件分类</h3>
          <p class="section-desc">找出分类准确率偏低、需要调整 Prompt 的类别。</p>
          <el-table :data="stats.byCategory || []" border size="small">
            <el-table-column prop="category" label="分类" width="120" />
            <el-table-column prop="total" label="反馈数" width="90" />
            <el-table-column prop="agree" label="认可" width="80" />
            <el-table-column label="准确率">
              <template #default="{ row }">{{ accuracyText(row.accuracy) }}</template>
            </el-table-column>
            <template #empty><span class="sub-text">暂无数据</span></template>
          </el-table>
        </div>
      </div>

      <!-- 分歧样本 -->
      <div class="section-card">
        <div class="section-header">
          <div>
            <h3>纠正样本</h3>
            <p class="section-desc">
              用户认为分析有误的邮件，可点开核对原文。「认可」的邮件不在本列表里
              —— 它们已经计入上方的准确率。
            </p>
          </div>
        </div>

        <el-table :data="feedbackList" v-loading="listLoading" border stripe>
          <el-table-column prop="subject" label="邮件主题" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <el-link type="primary" @click="openMail(row.mailId)">
                {{ row.subject || '（无主题）' }}
              </el-link>
            </template>
          </el-table-column>
          <el-table-column prop="userEmail" label="反馈人" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.userEmail">{{ row.userEmail }}</span>
              <span v-else class="sub-text">用户#{{ row.userId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="模型判定" width="170">
            <template #default="{ row }">
              <span class="sub-text">{{ row.sourceAtFeedback === 'LLM' ? '大模型' : '规则' }}</span>
              <span> · {{ row.categoryAtFeedback || '未分类' }}</span>
              <span v-if="row.isSpamAtFeedback === 1"> · 判为垃圾</span>
            </template>
          </el-table-column>
          <el-table-column label="用户修正" width="160">
            <template #default="{ row }">
              <span v-if="row.correctedCategory || row.correctedSpam !== null">
                {{ row.correctedCategory || '' }}
                <span v-if="row.correctedSpam === 1">（垃圾）</span>
                <span v-else-if="row.correctedSpam === 0">（非垃圾）</span>
              </span>
              <span v-else class="sub-text">未指明</span>
            </template>
          </el-table-column>
          <el-table-column prop="comment" label="备注" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.comment">{{ row.comment }}</span>
              <span v-else class="sub-text">-</span>
            </template>
          </el-table-column>
          <el-table-column label="提交时间" width="160">
            <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
          </el-table-column>
          <template #empty><span class="sub-text">暂无纠正记录</span></template>
        </el-table>

        <el-pagination
          v-if="listTotal > 0"
          class="pagination"
          background
          layout="total, prev, pager, next"
          :total="listTotal"
          :current-page="listPage"
          :page-size="listPageSize"
          @current-change="handlePageChange"
        />
      </div>
    </template>

    <!-- LLM 调用监控：与人工反馈互为印证 ——
         人工准确率说"判得准不准"，这里说"调用本身健不健康" -->
    <div class="section-card">
      <div class="section-header">
        <div>
          <h3>大模型调用监控</h3>
          <p class="section-desc">
            成功率 = 成功 / （总调用 − 未配置密钥）。「未配置密钥」不是失败，
            把它算进分母会让一个刚部署、大家都还没配 Key 的系统显示 0%。
          </p>
        </div>
        <el-select v-model="llmDays" style="width: 120px" @change="loadLlmStats">
          <el-option label="近 7 天" :value="7" />
          <el-option label="近 1 天" :value="1" />
          <el-option label="近 30 天" :value="30" />
        </el-select>
      </div>

      <div class="stat-cards">
        <div class="stat-card">
          <div class="stat-value">{{ llm.overview?.calls || 0 }}</div>
          <div class="stat-label">调用次数</div>
        </div>
        <div class="stat-card highlight">
          <div class="stat-value">{{ successRateText }}</div>
          <div class="stat-label">成功率</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ llm.overview?.timeout || 0 }}</div>
          <div class="stat-label">超时</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ llm.overview?.parseError || 0 }}</div>
          <div class="stat-label">解析失败</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ llm.overview?.skippedNoKey || 0 }}</div>
          <div class="stat-label">未配密钥</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ llm.avgLatencyMs || 0 }}</div>
          <div class="stat-label">平均延迟(ms)</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ llm.overview?.totalTokens || 0 }}</div>
          <div class="stat-label">消耗 token</div>
        </div>
      </div>

      <h4 class="sub-title">按收件人分布（前 20）</h4>
      <p class="section-desc">
        分组键是「被分析邮件的收件人」，不是「密钥的归属者」——
        这里回答的是谁的邮件没拿到大模型结论。
      </p>
      <el-table :data="llm.byUser || []" border size="small">
        <el-table-column label="收件人" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.userLabel || `用户#${row.userId}` }}</template>
        </el-table-column>
        <el-table-column prop="calls" label="调用" width="80" />
        <el-table-column prop="success" label="成功" width="80" />
        <el-table-column prop="notSuccess" label="未成功" width="90" />
        <el-table-column prop="totalTokens" label="token" width="100" />
        <template #empty><span class="sub-text">暂无调用记录</span></template>
      </el-table>

      <h4 class="sub-title">分析结果状态分布</h4>
      <div class="stat-cards">
        <div class="stat-card">
          <div class="stat-value">{{ analysisStats.DONE || 0 }}</div>
          <div class="stat-label">已完成</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ analysisStats.RUNNING || 0 }}</div>
          <div class="stat-label">分析中</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ analysisStats.PENDING || 0 }}</div>
          <div class="stat-label">待分析</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ analysisStats.FAILED || 0 }}</div>
          <div class="stat-label">失败</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ analysisStats.orphanFailed || 0 }}</div>
          <div class="stat-label">中断遗留</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ analysisStats.totalRevision || 0 }}</div>
          <div class="stat-label">重跑次数合计</div>
        </div>
      </div>
      <p class="section-desc">
        「分析中」长期偏高说明管线卡住；「重跑次数合计」持续增长说明
        "内容未变则不重跑"的幂等判断没有生效，同一封邮件在反复花钱。
      </p>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getFeedbackStats, listFeedback, getLlmStats, getAnalysisStats } from '@/api/admin'

const router = useRouter()

const stats = ref({})
const unavailable = ref(false)

/** 总体指标在 overview 下，分组在 bySource / byCategory / byModel */
const overview = computed(() => stats.value.overview || {})

const feedbackList = ref([])
const listTotal = ref(0)
const listPage = ref(1)
const listPageSize = ref(20)
const listLoading = ref(false)

const llm = ref({})
const llmDays = ref(7)
const analysisStats = ref({})

/** 成功率由后端算好（分母已扣掉"未配置密钥"）；null 表示没有可算的调用 */
const successRateText = computed(() => {
  const rate = llm.value.overview?.successRate
  if (rate === null || rate === undefined) return '-'
  return `${(Number(rate) * 100).toFixed(1)}%`
})

/**
 * 准确率 / 成功率在后端都是 0-1 的小数（见 FeedbackServiceImpl.withRates），
 * 展示时才乘 100。后端存比率、前端负责百分号 —— 免得一个数字有两位小数
 * 的歧义（"0.9" 到底是 0.9% 还是 90%）
 */
function accuracyText(value) {
  if (value === null || value === undefined) return '-'
  return `${(Number(value) * 100).toFixed(1)}%`
}

function formatTime(value) {
  if (!value) return '-'
  if (Array.isArray(value)) {
    const [y, m, d, h = 0, mi = 0, s = 0] = value
    return `${y}-${pad(m)}-${pad(d)} ${pad(h)}:${pad(mi)}:${pad(s)}`
  }
  return String(value).replace('T', ' ').slice(0, 19)
}

function pad(n) {
  return String(n).padStart(2, '0')
}

function openMail(mailId) {
  if (mailId) {
    router.push(`/mail/${mailId}`)
  }
}

async function loadStats() {
  try {
    const res = await getFeedbackStats()
    stats.value = res.data || {}
    // 一条反馈都没有时展示空状态，避免一堆 0 与 "-" 让人误以为统计坏了
    unavailable.value = !overview.value.total
  } catch (e) {
    unavailable.value = true
  }
}

async function loadList() {
  listLoading.value = true
  try {
    const res = await listFeedback({
      page: listPage.value,
      pageSize: listPageSize.value
    })
    // 后端返回的是 { total, page, pageSize, list }
    feedbackList.value = res.data?.list || []
    listTotal.value = res.data?.total || 0
  } catch (e) {
    feedbackList.value = []
    listTotal.value = 0
  } finally {
    listLoading.value = false
  }
}

async function loadLlmStats() {
  try {
    const res = await getLlmStats(llmDays.value)
    llm.value = res.data || {}
  } catch (e) {
    llm.value = {}
  }
}

async function loadAnalysisStats() {
  try {
    const res = await getAnalysisStats()
    analysisStats.value = res.data || {}
  } catch (e) {
    analysisStats.value = {}
  }
}

function handlePageChange(newPage) {
  listPage.value = newPage
  loadList()
}

onMounted(async () => {
  await loadStats()
  // 统计不可用（后端未接入/无数据）时不必再拉明细
  if (!unavailable.value) {
    loadList()
  }
  // 调用监控与反馈统计相互独立：没有反馈时它照样有内容可看
  loadLlmStats()
  loadAnalysisStats()
})
</script>

<style scoped>
.admin-feedback-page {
  max-width: 1200px;
}

.page-desc {
  color: #909399;
  margin-bottom: 20px;
}

.stat-cards {
  display: flex;
  gap: 16px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.stat-card {
  flex: 1;
  min-width: 120px;
  padding: 16px 20px;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  text-align: center;
}

.stat-card.highlight {
  background: #ecf5ff;
  border-color: #b3d8ff;
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}

.stat-label {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}

.group-row {
  display: flex;
  gap: 20px;
  flex-wrap: wrap;
}

.group-row .section-card {
  flex: 1;
  min-width: 380px;
}

.section-card {
  padding: 20px 24px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  margin-bottom: 20px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.section-card h3 {
  margin: 0 0 6px;
  font-size: 16px;
  color: #303133;
}

.section-desc {
  color: #909399;
  font-size: 13px;
  margin-bottom: 14px;
  line-height: 1.6;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.sub-title {
  margin: 20px 0 10px;
  font-size: 14px;
  color: #303133;
}

.sub-text {
  color: #909399;
  font-size: 12px;
}
</style>
