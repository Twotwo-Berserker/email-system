<template>
  <div class="mail-accounts-page">
    <h2>📮 邮箱账户</h2>
    <p class="page-desc">
      这里有两种拿到邮箱的途径：<b>绑定你已有的外部邮箱</b>（需要授权码），
      或者<b>领取一个本系统域名下的地址</b>（不需要授权码）。
    </p>

    <el-alert type="info" show-icon :closable="false" style="margin-bottom: 20px">
      <template #title>绑定外部邮箱需要授权码；领取本域地址不需要</template>
      <template #default>
        <div class="hint-line">
          <b>绑定外部邮箱</b> —— 填地址和授权码两步。系统读取你的邮箱需要你授权，
          授权码由邮箱服务商签发（QQ/网易叫「授权码」，Gmail 叫「应用专用密码」），
          识别出你的邮箱后界面会直接告诉你去哪里生成。
        </div>
        <div class="hint-line">
          <b>领取本域地址</b> —— 只填用户名，没有授权码这一步。收信由 Cloudflare
          在本项目域名上直接接收并转发进来，系统从不登录别人的邮箱，因此没有可提供的授权码。
        </div>
      </template>
    </el-alert>

    <div class="toolbar">
      <el-button type="primary" @click="openQuickBind">绑定外部邮箱</el-button>
      <el-button v-if="inbound.enabled" type="success" plain @click="openInboundBind">
        领取本域地址
      </el-button>
      <el-button @click="openManualCreate">手动配置</el-button>
      <el-button :loading="loading" @click="loadAccounts">刷新</el-button>
    </div>

    <el-empty v-if="!loading && accounts.length === 0" description="还没有邮箱账户" />

    <el-card v-for="account in accounts" :key="account.id" class="account-card" shadow="never">
      <div class="card-head">
        <div>
          <span class="address">{{ account.emailAddress }}</span>
          <span class="sub-text" v-if="account.displayName">（{{ account.displayName }}）</span>
        </div>
        <div class="card-head-right">
          <el-tag v-if="account.cloudflareRouting" type="success" size="small" effect="plain">
            本域地址 · 无需授权码
          </el-tag>
          <el-tag :type="account.enabled === 1 ? 'success' : 'info'" size="small">
            {{ account.enabled === 1 ? '已启用' : '已停用' }}
          </el-tag>
          <el-tag v-if="account.lastSyncStatus" :type="syncTagType(account.lastSyncStatus)" size="small">
            {{ syncStatusLabel(account.lastSyncStatus) }}
          </el-tag>
        </div>
      </div>

      <!-- 本域地址：没有 SMTP/IMAP 服务器，也没有授权码，卡片内容完全不同 -->
      <div class="card-body" v-if="account.cloudflareRouting">
        <div class="kv">
          <span class="k">收信</span>
          <span class="v">Cloudflare 收到本项目域名的信后直接推送给系统</span>
        </div>
        <div class="kv">
          <span class="k">发信</span>
          <span class="v">{{ outboundText }}</span>
          <el-tag v-if="inbound.outboundAvailable" type="success" size="small" effect="plain">
            中继已就绪
          </el-tag>
          <el-tag v-else type="warning" size="small" effect="plain">仅能收信</el-tag>
        </div>
        <div class="note" v-if="!inbound.outboundAvailable">
          发信依赖本系统的发信中继，当前部署尚未配置。你仍然能收到这个地址的来信。
        </div>
        <div class="kv">
          <span class="k">最近收信</span>
          <span class="v">{{ formatTime(account.lastSyncTime) || '尚未收到邮件' }}</span>
        </div>
        <div class="sync-error" v-if="account.lastSyncError">{{ account.lastSyncError }}</div>
      </div>

      <div class="card-body" v-else>
        <div class="kv">
          <span class="k">SMTP</span>
          <span class="v">{{ account.smtpHost || '未配置' }}:{{ account.smtpPort || '-' }}{{ account.smtpSsl ? ' (SSL)' : '' }}</span>
          <el-tag :type="account.hasSmtpPassword ? 'success' : 'info'" size="small" effect="plain">
            {{ account.hasSmtpPassword ? '授权码已保存' : '无授权码' }}
          </el-tag>
        </div>
        <div class="kv">
          <span class="k">IMAP</span>
          <span class="v">{{ account.imapHost || '未配置' }}:{{ account.imapPort || '-' }}{{ account.imapSsl ? ' (SSL)' : '' }}</span>
          <el-tag :type="account.hasImapPassword ? 'success' : 'info'" size="small" effect="plain">
            {{ account.hasImapPassword ? '授权码已保存' : '无授权码' }}
          </el-tag>
        </div>
        <div class="kv">
          <span class="k">最近同步</span>
          <span class="v">{{ formatTime(account.lastSyncTime) || '尚未同步' }}</span>
        </div>
        <div class="sync-error" v-if="account.lastSyncError">{{ account.lastSyncError }}</div>
      </div>

      <div class="card-actions">
        <!-- 测试连接 / 立即收信都要去连账户的服务器。本域地址没有服务器可连，
             收信也不是"轮询"而是被推送，放两个按不动的按钮只会让人以为坏了 -->
        <template v-if="!account.cloudflareRouting">
          <el-button size="small" :loading="testing === account.id" @click="handleTest(account)">
            测试连接
          </el-button>
          <el-button size="small" :loading="syncing === account.id" @click="handleSync(account)">
            立即收信
          </el-button>
        </template>
        <el-button size="small" @click="openEdit(account)">
          {{ account.cloudflareRouting ? '改显示名' : '修改' }}
        </el-button>
        <el-button size="small" type="danger" plain @click="handleDelete(account)">解绑</el-button>
      </div>
    </el-card>

    <!-- ============ 一键绑定 ============ -->
    <el-dialog v-model="quickVisible" title="绑定外部邮箱" width="680px">
      <el-form :model="quickForm" label-width="96px">
        <el-form-item label="邮箱地址" required>
          <el-input
            v-model="quickForm.emailAddress"
            placeholder="yourname@qq.com"
            clearable
            @keyup.enter="handleQuickBind"
          />
          <div class="field-hint">填完整地址即可，服务器地址由系统识别</div>
        </el-form-item>
      </el-form>

      <!-- 识别结果：确认服务商，并告诉用户授权码去哪里拿 -->
      <div v-if="detecting" class="detect-card unknown">
        <span class="muted">正在识别邮箱服务商…</span>
      </div>

      <div v-else-if="detection" class="detect-card" :class="detectTone">
        <div class="detect-head">
          <el-tag :type="detection.recognized ? 'success' : 'warning'" size="small" effect="dark">
            {{ detection.recognized ? ('已识别：' + (detection.providerName || '未知服务商')) : '未能识别的服务商' }}
          </el-tag>
          <span class="detect-sub" v-if="detection.recognized">服务器地址与加密方式将自动填写</span>
          <span class="detect-sub" v-else>将尝试常见的服务器命名</span>
        </div>

        <!-- 服务商在协议层就不开放：直接劝退，别让用户白试 -->
        <div v-if="!detection.smtpSupported && !detection.imapSupported" class="blocked">
          <div class="blocked-title">该服务商不支持标准的邮件收发协议</div>
          <div class="blocked-body">{{ detection.unsupportedReason || detection.warning || '无法通过 SMTP/IMAP 收发邮件' }}</div>
        </div>

        <template v-else>
          <div class="server-line" v-if="detection.smtpSupported">
            <span class="server-k">发信</span>
            <span class="server-v">{{ firstCandidate(detection.smtpCandidates) }}</span>
          </div>
          <div class="server-line" v-else>
            <span class="server-k">发信</span>
            <span class="server-v muted">该服务商不提供 SMTP</span>
          </div>

          <div class="server-line" v-if="detection.imapSupported">
            <span class="server-k">收信</span>
            <span class="server-v">{{ firstCandidate(detection.imapCandidates) }}</span>
          </div>
          <div class="server-line" v-else>
            <span class="server-k">收信</span>
            <span class="server-v muted">该服务商不提供 IMAP</span>
          </div>

          <div class="guide" v-if="detection.guideSteps && detection.guideSteps.length">
            <div class="guide-title">授权码在这里生成</div>
            <ol class="guide-steps">
              <li v-for="(step, i) in detection.guideSteps" :key="i">{{ step }}</li>
            </ol>
            <el-link
              v-if="detection.guideUrl"
              type="primary"
              :href="detection.guideUrl"
              target="_blank"
              rel="noopener noreferrer"
            >
              打开 {{ detection.guideUrl }} ↗
            </el-link>
          </div>

          <div class="warn" v-if="detection.warning">⚠️ {{ detection.warning }}</div>
          <div class="note" v-if="detection.note">· {{ detection.note }}</div>
        </template>
      </div>

      <el-form :model="quickForm" label-width="96px" style="margin-top: 16px">
        <el-form-item label="授权码" required>
          <el-input
            v-model="quickForm.password"
            type="password"
            show-password
            placeholder="邮箱授权码，不是邮箱登录密码"
            @keyup.enter="handleQuickBind"
          />
          <div class="field-hint">
            各服务商叫法不同：QQ/网易叫「授权码」，Gmail 叫「应用专用密码」。
            直接粘贴即可，首尾空格会被忽略。
          </div>
        </el-form-item>

        <el-form-item label="显示名">
          <el-input v-model="quickForm.displayName" placeholder="选填，收件人看到的发件人名称" />
        </el-form-item>

        <el-form-item label="同时收信">
          <el-checkbox v-model="quickForm.receiveEnabled">
            收取这个邮箱的来信（关闭则只用它对外发信）
          </el-checkbox>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="quickVisible = false">取消</el-button>
        <el-button link type="primary" @click="switchToManual">改用手动配置</el-button>
        <el-button type="primary" :loading="binding" @click="handleQuickBind">绑定</el-button>
      </template>
    </el-dialog>

    <!-- ============ 领取本域地址（无需授权码） ============ -->
    <el-dialog
      v-model="inboundVisible"
      :title="inboundEditingId ? '编辑本域地址' : '领取本域地址'"
      width="560px"
    >
      <el-alert
        type="success"
        show-icon
        :closable="false"
        style="margin-bottom: 16px"
        title="这条路径没有授权码这一步"
        :description="inbound.message || '收信由 Cloudflare 在本项目域名上直接接收后转发给系统，系统不会登录任何外部邮箱。'"
      />

      <el-form :model="inboundForm" label-width="96px">
        <el-form-item label="地址" required>
          <!-- 编辑态不许改地址：后端 update 只认显示名与启用开关，
               让输入框看起来能改、改了却没生效是最糟的一种交互 -->
          <el-input v-if="inboundEditingId" :model-value="inboundEditingAddress" disabled />
          <el-input v-else v-model="inboundForm.localPart" placeholder="yourname" @keyup.enter="handleInboundSave">
            <template #append v-if="inbound.domains.length > 1">
              <el-select v-model="inboundForm.domain" style="width: 190px">
                <el-option v-for="d in inbound.domains" :key="d" :label="'@' + d" :value="d" />
              </el-select>
            </template>
            <template #append v-else-if="defaultInboundDomain">@{{ defaultInboundDomain }}</template>
          </el-input>
          <template v-if="!inboundEditingId">
            <div class="field-hint">
              只需填用户名。只允许小写字母、数字与 <code>. _ + -</code>，不能以符号开头或结尾。
            </div>
            <div class="field-hint" v-if="inboundAddressPreview">
              将领取：<b>{{ inboundAddressPreview }}</b>
            </div>
          </template>
          <div class="field-hint" v-else>
            地址领取后不可修改 —— 它同时是别人发信的收件地址。如需换地址，请解绑后重新领取。
          </div>
        </el-form-item>

        <el-form-item label="显示名">
          <el-input v-model="inboundForm.displayName" placeholder="选填，收件人看到的发件人名称" />
        </el-form-item>

        <el-form-item label="启用" v-if="inboundEditingId">
          <el-switch v-model="inboundForm.enabled" />
          <div class="field-hint">停用后不再接收这个地址的来信</div>
        </el-form-item>
      </el-form>

      <!-- 领了地址却发不出信，是这条路径最容易踩的坑：提前说清楚 -->
      <el-alert
        v-if="!inbound.outboundAvailable"
        type="warning"
        show-icon
        :closable="false"
        title="当前部署只能收信，不能发信"
        description="本域地址发信依赖管理员配置的发信中继。你仍然能正常收到这个地址的来信。"
      />

      <template #footer>
        <el-button @click="inboundVisible = false">取消</el-button>
        <el-button type="primary" :loading="inboundSaving" @click="handleInboundSave">
          {{ inboundEditingId ? '保存' : '领取' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ============ 手动配置 / 修改 ============ -->
    <el-dialog v-model="dialogVisible" :title="editingId ? '修改邮箱配置' : '手动配置邮箱'" width="640px">
      <el-alert
        v-if="!editingId"
        type="info"
        show-icon
        :closable="false"
        style="margin-bottom: 16px"
        title="这条路需要你自己填服务器地址"
        description="不确定的话，用「一键绑定」更快 —— 它会自动识别并验证。"
      />
      <el-form :model="form" label-width="120px">
        <el-form-item label="邮箱地址" required>
          <el-input v-model="form.emailAddress" placeholder="yourname@qq.com" />
          <div class="field-hint">必须是这个邮箱本身 —— 发信时系统用它作为发件人</div>
        </el-form-item>

        <el-form-item label="显示名">
          <el-input v-model="form.displayName" placeholder="选填，收件人看到的发件人名称" />
        </el-form-item>

        <el-divider content-position="left">发信服务器（SMTP）</el-divider>
        <el-form-item label="服务器">
          <el-input v-model="form.smtpHost" placeholder="smtp.qq.com" />
        </el-form-item>
        <el-form-item label="端口">
          <el-input-number v-model="form.smtpPort" :min="1" :max="65535" placeholder="留空按 SSL 推导" />
          <el-checkbox v-model="smtpSslChecked" style="margin-left: 12px">使用 SSL</el-checkbox>
          <div class="field-hint">QQ / 163 / Gmail 都用 SSL。不勾选则按 587 + STARTTLS 连接</div>
        </el-form-item>
        <el-form-item label="授权码">
          <el-input
            v-model="form.smtpPassword"
            type="password"
            show-password
            :placeholder="editingHasSmtpPassword ? '已保存，留空则保持不变' : '邮箱授权码，不是登录密码'"
          />
        </el-form-item>

        <el-divider content-position="left">收信服务器（IMAP）</el-divider>
        <el-form-item label="服务器">
          <el-input v-model="form.imapHost" placeholder="imap.qq.com" />
          <div class="field-hint">只发信不收信的话可以留空</div>
        </el-form-item>
        <el-form-item label="端口">
          <el-input-number v-model="form.imapPort" :min="1" :max="65535" placeholder="留空按 SSL 推导" />
          <el-checkbox v-model="imapSslChecked" style="margin-left: 12px">使用 SSL</el-checkbox>
        </el-form-item>
        <el-form-item label="授权码">
          <el-input
            v-model="form.imapPassword"
            type="password"
            show-password
            :placeholder="editingHasImapPassword ? '已保存，留空则保持不变' : '通常与 SMTP 是同一个授权码'"
          />
        </el-form-item>

        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
          <div class="field-hint">停用后不再收信，也不能用它对外发信</div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import {
  listMyMailAccounts,
  createMailAccount,
  updateMailAccount,
  deleteMailAccount,
  testMailAccount,
  syncMailAccount,
  detectMailProvider,
  quickBindMailAccount,
  fetchInboundCapabilities,
  bindInboundAddress
} from '@/api/mailAccount'
import { ElMessage, ElMessageBox } from 'element-plus'

const accounts = ref([])
const loading = ref(false)
const saving = ref(false)
const testing = ref(null)
const syncing = ref(null)

/**
 * 本域地址的部署能力。
 * <p>
 * 默认全部关闭 —— 在拿到后端答复之前，"领取本域地址"入口不显示。
 * 反过来（先显示再消失）会让用户在页面加载的瞬间点进一个用不了的功能。
 * </p>
 */
const inbound = reactive({
  enabled: false,
  domains: [],
  outboundTransport: '',
  outboundAvailable: false,
  message: ''
})

/** 发信通道的展示文案，由后端的 transport 名映射而来 */
const OUTBOUND_LABELS = {
  resend: '通过 Resend 以本项目域名发出',
  smtp: '通过本系统的 SMTP 中继发出'
}

const outboundText = computed(() => {
  if (!inbound.outboundAvailable) return '暂不可用'
  return OUTBOUND_LABELS[inbound.outboundTransport] || '通过本系统的发信中继发出'
})

// ---- 领取 / 编辑本域地址 ----
const inboundVisible = ref(false)
const inboundSaving = ref(false)
const inboundEditingId = ref(null)
const inboundEditingAddress = ref('')

const inboundForm = reactive({
  localPart: '',
  domain: '',
  displayName: '',
  enabled: true
})

const defaultInboundDomain = computed(() => inbound.domains[0] || '')

/** 输入用户名时的实时预览，让用户领之前就看清完整地址 */
const inboundAddressPreview = computed(() => {
  const local = (inboundForm.localPart || '').trim()
  if (!local) return ''
  const domain = inboundForm.domain || defaultInboundDomain.value
  return domain ? `${local}@${domain}` : local
})

// ---- 一键绑定 ----
const quickVisible = ref(false)
const binding = ref(false)
const detecting = ref(false)
const detection = ref(null)

const quickForm = reactive({
  emailAddress: '',
  password: '',
  displayName: '',
  receiveEnabled: true
})

// ---- 手动配置 / 修改 ----
const dialogVisible = ref(false)
const editingId = ref(null)
/** 编辑态下是否已有授权码 —— 决定输入框的占位提示 */
const editingHasSmtpPassword = ref(false)
const editingHasImapPassword = ref(false)

const SYNC_LABELS = {
  SUCCESS: '同步正常',
  FAILED: '同步失败',
  AUTH_FAILED: '授权码错误'
}

function syncStatusLabel(status) {
  return SYNC_LABELS[status] || status || '-'
}

function syncTagType(status) {
  return status === 'SUCCESS' ? 'success' : 'danger'
}

const form = reactive({
  emailAddress: '',
  displayName: '',
  smtpHost: '',
  smtpPort: null,
  smtpSsl: 1,
  smtpUsername: '',
  smtpPassword: '',
  imapHost: '',
  imapPort: null,
  imapSsl: 1,
  imapUsername: '',
  imapPassword: '',
  enabled: true
})

// 表单里 SSL 存的是 1/0（后端列是 TINYINT），而 el-checkbox 要布尔值。
// 用带 setter 的 computed 桥接，比在提交与回填时到处转换清楚
const smtpSslChecked = computed({
  get: () => form.smtpSsl === 1,
  set: (v) => { form.smtpSsl = v ? 1 : 0 }
})
const imapSslChecked = computed({
  get: () => form.imapSsl === 1,
  set: (v) => { form.imapSsl = v ? 1 : 0 }
})

/** 识别卡片的整体色调 */
const detectTone = computed(() => {
  if (!detection.value) return 'unknown'
  if (!detection.value.smtpSupported && !detection.value.imapSupported) return 'blocked'
  return detection.value.recognized ? 'ok' : 'unknown'
})

function firstCandidate(list) {
  return (list && list.length) ? list[0] : '待连接测试确定'
}

// ==================== 一键绑定 ====================

function openQuickBind() {
  quickForm.emailAddress = ''
  quickForm.password = ''
  quickForm.displayName = ''
  quickForm.receiveEnabled = true
  detection.value = null
  detecting.value = false
  quickVisible.value = true
}

function switchToManual() {
  quickVisible.value = false
  openManualCreate()
}

/**
 * 边打字边识别服务商。
 * <p>
 * 不需要授权码就能调用，因此可以在用户还没决定是否绑定时就告诉他
 * "我认出你的邮箱了，授权码去这里拿"。这里传 mx=false ——
 * MX 反查要做 DNS，不能挂在打字这种高频动作上。
 * </p>
 */
let detectTimer = null
watch(() => quickForm.emailAddress, (value) => {
  clearTimeout(detectTimer)
  const email = (value || '').trim()

  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    detection.value = null
    detecting.value = false
    return
  }

  detecting.value = true
  detectTimer = setTimeout(async () => {
    try {
      const res = await detectMailProvider(email, false)
      // 请求返回时用户可能已经改了地址 —— 丢弃过期结果，
      // 否则会出现"卡片显示的域名和输入框里的对不上"
      if ((quickForm.emailAddress || '').trim() === email) {
        detection.value = res.data || null
      }
    } catch (e) {
      detection.value = null
    } finally {
      detecting.value = false
    }
  }, 450)
})

async function handleQuickBind() {
  const email = (quickForm.emailAddress || '').trim()
  if (!email) {
    ElMessage.warning('请填写邮箱地址')
    return
  }
  if (!quickForm.password) {
    ElMessage.warning('请填写邮箱授权码')
    return
  }

  binding.value = true
  try {
    const res = await quickBindMailAccount({
      emailAddress: email,
      password: quickForm.password,
      displayName: quickForm.displayName || undefined,
      receiveEnabled: quickForm.receiveEnabled
    })
    const result = res.data || {}
    quickVisible.value = false
    await loadAccounts()
    showBindOutcome(res.message, result)

    // 绑定成功就立刻收一次信 —— 让用户在几秒内看到"真的有邮件进来了"，
    // 而不是等到下一个轮询周期（默认 3 分钟）才开始怀疑有没有绑成功
    if (result.account && result.account.id && result.imapOk) {
      syncMailAccount(result.account.id)
        .then(loadAccounts)
        .catch(() => { /* 收信失败不影响绑定结果，卡片上会显示同步错误 */ })
    }
  } catch (e) {
    // quick-bind 的失败信息是多行的操作指引（含各协议的尝试明细和
    // 授权码获取步骤），轻提示承载不了，必须用弹窗完整展示
    showMultiline(e && e.message ? e.message : '绑定失败，请稍后重试', '绑定失败')
  } finally {
    binding.value = false
  }
}

/** 绑定结果是"两侧都通 / 只通一侧 / 都不通"之外的第四种：通了但有话要说 */
function showBindOutcome(message, result) {
  const notices = result.notices || []
  if (!notices.length) {
    ElMessage.success(message || '绑定成功')
    return
  }
  const lines = [message || '绑定成功', '']
  notices.forEach(n => lines.push('· ' + n))
  showMultiline(lines.join('\n'), '绑定结果')
}

/**
 * 多行文本弹窗。
 * <p>
 * 用 VNode + white-space: pre-wrap 而不是 dangerouslyUseHTMLString ——
 * 这些文本里包含用户填的邮箱地址和服务器返回的原始错误，
 * 走 HTML 渲染等于开了一个注入口子。
 * </p>
 */
function showMultiline(text, title) {
  return ElMessageBox.alert(
    h('div', { style: 'white-space: pre-wrap; line-height: 1.75; max-height: 55vh; overflow: auto;' },
      String(text || '')),
    title,
    { confirmButtonText: '知道了', customStyle: { maxWidth: '580px' } }
  ).catch(() => { /* 用户关闭弹窗 */ })
}

// ==================== 领取 / 编辑本域地址 ====================

/**
 * 问一次后端"这个部署能不能领本域地址"。
 * <p>
 * 拿不到就当没有这个功能（inbound 保持默认的全 false）——
 * 入口不显示，比显示出来再报错体验好得多。
 * </p>
 */
async function loadCapabilities() {
  try {
    const res = await fetchInboundCapabilities()
    const data = res.data || {}
    inbound.enabled = data.inboundEnabled === true
    inbound.domains = data.domains || []
    inbound.outboundTransport = data.outboundTransport || ''
    inbound.outboundAvailable = data.outboundAvailable === true
    inbound.message = data.message || ''
  } catch (e) {
    inbound.enabled = false
  }
}

function openInboundBind() {
  inboundEditingId.value = null
  inboundEditingAddress.value = ''
  inboundForm.localPart = ''
  inboundForm.domain = defaultInboundDomain.value
  inboundForm.displayName = ''
  inboundForm.enabled = true
  inboundVisible.value = true
}

function openInboundEdit(account) {
  inboundEditingId.value = account.id
  inboundEditingAddress.value = account.emailAddress || ''
  inboundForm.displayName = account.displayName || ''
  inboundForm.enabled = account.enabled === 1
  inboundVisible.value = true
}

async function handleInboundSave() {
  const local = (inboundForm.localPart || '').trim()
  if (!inboundEditingId.value && !local) {
    ElMessage.warning('请填写地址的用户名部分')
    return
  }

  inboundSaving.value = true
  try {
    if (inboundEditingId.value) {
      // 只提交显示名与启用开关：地址、服务器、授权码都不该由这个弹窗改动
      await updateMailAccount(inboundEditingId.value, {
        emailAddress: inboundEditingAddress.value,
        displayName: inboundForm.displayName,
        enabled: inboundForm.enabled
      })
      ElMessage.success('已保存')
    } else {
      const res = await bindInboundAddress({
        address: inboundAddressPreview.value,
        displayName: inboundForm.displayName || undefined
      })
      const account = (res.data || {}).emailAddress
      ElMessage.success(account ? `已领取 ${account}` : '领取成功')
    }
    inboundVisible.value = false
    await loadAccounts()
  } catch (e) {
    // 失败原因（地址被占用、域名不属于本实例）是多行的操作指引，弹窗完整展示
    showMultiline(e && e.message ? e.message : '操作失败，请稍后重试', '未能完成')
  } finally {
    inboundSaving.value = false
  }
}

// ==================== 手动配置 / 修改 ====================

function resetForm() {
  form.emailAddress = ''
  form.displayName = ''
  form.smtpHost = ''
  form.smtpPort = null
  form.smtpSsl = 1
  form.smtpUsername = ''
  form.smtpPassword = ''
  form.imapHost = ''
  form.imapPort = null
  form.imapSsl = 1
  form.imapUsername = ''
  form.imapPassword = ''
  form.enabled = true
  editingHasSmtpPassword.value = false
  editingHasImapPassword.value = false
}

function openManualCreate() {
  editingId.value = null
  resetForm()
  dialogVisible.value = true
}

function openEdit(account) {
  // 本域地址没有服务器也没有授权码，手动配置那张表对它一格都不适用
  if (account.cloudflareRouting) {
    openInboundEdit(account)
    return
  }
  editingId.value = account.id
  resetForm()
  form.emailAddress = account.emailAddress || ''
  form.displayName = account.displayName || ''
  form.smtpHost = account.smtpHost || ''
  form.smtpPort = account.smtpPort ?? null
  form.smtpSsl = account.smtpSsl ?? 1
  form.smtpUsername = account.smtpUsername || ''
  form.imapHost = account.imapHost || ''
  form.imapPort = account.imapPort ?? null
  form.imapSsl = account.imapSsl ?? 1
  form.imapUsername = account.imapUsername || ''
  form.enabled = account.enabled === 1
  editingHasSmtpPassword.value = account.hasSmtpPassword === true
  editingHasImapPassword.value = account.hasImapPassword === true
  dialogVisible.value = true
}

async function loadAccounts() {
  loading.value = true
  try {
    const res = await listMyMailAccounts()
    accounts.value = res.data || []
  } catch (e) {
    accounts.value = []
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  if (!form.emailAddress.trim()) {
    ElMessage.warning('请填写邮箱地址')
    return
  }
  if (!form.smtpHost.trim() && !form.imapHost.trim()) {
    ElMessage.warning('请至少填写 SMTP 或 IMAP 服务器地址')
    return
  }

  const payload = { ...form, emailAddress: form.emailAddress.trim() }
  saving.value = true
  try {
    if (editingId.value) {
      await updateMailAccount(editingId.value, payload)
      ElMessage.success('配置已更新')
    } else {
      await createMailAccount(payload)
      ElMessage.success('邮箱绑定成功')
    }
    dialogVisible.value = false
    await loadAccounts()
  } catch (e) {
    // 错误提示由 request 拦截器统一弹出
  } finally {
    saving.value = false
  }
}

async function handleTest(account) {
  testing.value = account.id
  try {
    const res = await testMailAccount(account.id)
    const result = res.data || {}
    if (result.smtpOk && result.imapOk) {
      ElMessage.success('SMTP 与 IMAP 连接均正常')
    } else if (result.smtpOk) {
      ElMessage.warning(`SMTP 正常；IMAP：${result.imapError || '不可用'}`)
    } else if (result.imapOk) {
      ElMessage.warning(`IMAP 正常；SMTP：${result.smtpError || '不可用'}`)
    } else {
      showMultiline(
        `SMTP：${result.smtpError || '不可用'}\n\nIMAP：${result.imapError || '不可用'}`,
        '连接测试未通过'
      )
    }
  } catch (e) {
    // 错误提示由 request 拦截器统一弹出
  } finally {
    testing.value = null
  }
}

async function handleSync(account) {
  syncing.value = account.id
  try {
    const res = await syncMailAccount(account.id)
    const saved = res.data?.syncedCount
    ElMessage.success(saved ? `收取完成，新增 ${saved} 封邮件` : '收取完成，没有新邮件')
    await loadAccounts()
  } catch (e) {
    // 错误提示由 request 拦截器统一弹出
  } finally {
    syncing.value = null
  }
}

async function handleDelete(account) {
  const consequence = account.cloudflareRouting
    // 本域地址解绑后会立即被别人领走，这一点必须说在前面
    ? `解绑后 ${account.emailAddress} 会被释放，其他用户随时可以领走它，之后寄到这个地址的信将不再进入你的收件箱。`
    : '解绑后将不再收取该邮箱的来信，也不能再通过它对外发信。'
  try {
    await ElMessageBox.confirm(
      `解绑 ${account.emailAddress}？${consequence}已收到的邮件不受影响。`,
      '解绑邮箱',
      { type: 'warning', confirmButtonText: '确定解绑', cancelButtonText: '取消' }
    )
  } catch (e) {
    return // 用户取消
  }
  try {
    await deleteMailAccount(account.id)
    ElMessage.success('已解绑')
    await loadAccounts()
  } catch (e) {
    // 错误提示由 request 拦截器统一弹出
  }
}

function formatTime(value) {
  if (!value) return ''
  if (Array.isArray(value)) {
    const [y, m, d, h = 0, mi = 0, s = 0] = value
    return `${y}-${pad(m)}-${pad(d)} ${pad(h)}:${pad(mi)}:${pad(s)}`
  }
  return String(value).replace('T', ' ').slice(0, 19)
}

function pad(n) {
  return String(n).padStart(2, '0')
}

onMounted(() => {
  loadAccounts()
  loadCapabilities()
})
</script>

<style scoped>
.mail-accounts-page {
  max-width: 860px;
}

.page-desc {
  color: #909399;
  margin-bottom: 20px;
}

.hint-line {
  line-height: 1.7;
}

.toolbar {
  margin-bottom: 16px;
}

.account-card {
  margin-bottom: 16px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f2f5;
}

.address {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.card-head-right {
  display: flex;
  gap: 8px;
}

.card-body {
  padding: 12px 0;
}

.kv {
  display: flex;
  align-items: center;
  gap: 10px;
  line-height: 2;
  font-size: 13px;
}

.kv .k {
  width: 70px;
  color: #909399;
  flex-shrink: 0;
}

.kv .v {
  color: #606266;
}

.sync-error {
  margin-top: 8px;
  padding: 8px 10px;
  background: #fef0f0;
  border-radius: 4px;
  color: #f56c6c;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
}

.card-actions {
  padding-top: 12px;
  border-top: 1px solid #f0f2f5;
}

.sub-text {
  color: #909399;
  font-size: 12px;
}

.field-hint {
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
  margin-top: 4px;
}

.field-hint code {
  padding: 0 4px;
  background: #f4f4f5;
  border-radius: 3px;
  font-family: ui-monospace, Menlo, Consolas, monospace;
}

/* ---------- 识别结果卡片 ---------- */

.detect-card {
  margin: 0 0 4px 8px;
  padding: 12px 14px;
  border-radius: 8px;
  border: 1px solid #e4e7ed;
  background: #fafafa;
  font-size: 13px;
  line-height: 1.7;
}

.detect-card.ok {
  border-color: #b3e19d;
  background: #f0f9eb;
}

.detect-card.unknown {
  border-color: #f3d19e;
  background: #fdf6ec;
}

.detect-card.blocked {
  border-color: #fab6b6;
  background: #fef0f0;
}

.detect-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.detect-sub {
  color: #909399;
  font-size: 12px;
}

.server-line {
  display: flex;
  gap: 10px;
}

.server-k {
  width: 34px;
  color: #909399;
  flex-shrink: 0;
}

.server-v {
  color: #303133;
  font-family: ui-monospace, Menlo, Consolas, monospace;
  font-size: 12px;
  word-break: break-all;
}

.muted {
  color: #909399;
}

.guide {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed #dcdfe6;
}

.guide-title {
  font-weight: 600;
  color: #303133;
  margin-bottom: 4px;
}

.guide-steps {
  margin: 0 0 6px;
  padding-left: 20px;
  color: #606266;
}

.guide-steps li {
  margin: 2px 0;
}

.warn {
  margin-top: 8px;
  color: #e6a23c;
}

.note {
  margin-top: 4px;
  color: #909399;
  font-size: 12px;
}

.blocked-title {
  font-weight: 600;
  color: #f56c6c;
  margin-bottom: 4px;
}

.blocked-body {
  color: #606266;
}
</style>
