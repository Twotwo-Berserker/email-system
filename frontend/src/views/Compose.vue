<template>
  <div class="compose-page">
    <div class="compose-header">
      <h2>
        <template v-if="draftId">✏️ 编辑草稿</template>
        <template v-else>✉️ 写邮件</template>
      </h2>
      <div>
        <el-button :loading="savingDraft" @click="saveDraft">存草稿</el-button>
        <el-button type="primary" :loading="sending" @click="handleSend">发送</el-button>
      </div>
    </div>

    <el-form :model="form" label-width="80px" class="compose-form">
      <el-form-item label="发件人">
        <el-input :model-value="userEmail" disabled />
      </el-form-item>
      <el-form-item label="收件人" required>
        <el-input v-model="form.receiverIds" placeholder="输入收件人邮箱，多个用逗号分隔" />
      </el-form-item>
      <el-form-item label="抄送">
        <el-input v-model="form.ccIds" placeholder="抄送人邮箱，多个用逗号分隔（选填）" />
      </el-form-item>
      <el-form-item label="主题" required>
        <el-input v-model="form.subject" placeholder="请输入邮件主题" />
      </el-form-item>
      <el-form-item label="正文" required>
        <div class="editor-wrapper">
          <el-input
            v-model="form.body"
            type="textarea"
            :rows="12"
            placeholder="请输入邮件正文…"
          />
        </div>
      </el-form-item>
      <el-form-item label="附件">
        <div class="attachment-area">
          <el-upload
            ref="uploadRef"
            :auto-upload="false"
            :limit="5"
            :on-change="handleFileChange"
            :on-remove="handleFileRemove"
            multiple
          >
            <el-button type="primary" plain>
              <el-icon><Upload /></el-icon> 添加附件
            </el-button>
            <template #tip>
              <span class="upload-tip">单个附件不超过 50MB</span>
            </template>
          </el-upload>
          <div class="file-list">
            <el-tag
              v-for="(file, index) in fileList"
              :key="index"
              closable
              @close="handleFileRemove(file)"
              style="margin: 4px"
            >
              {{ file.name }}
            </el-tag>
          </div>
        </div>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { sendMail, saveDraft as saveDraftApi, updateDraft, sendDraft, mailDetail } from '@/api/mail'
import { uploadAttachment } from '@/api/attachment'
import { ElMessage } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const sending = ref(false)
const savingDraft = ref(false)
const uploadRef = ref()
const fileList = ref([]) // 本地待上传文件
const uploadedIds = ref([]) // 已上传附件ID
const draftId = ref(null) // 当前编辑的草稿ID

const userEmail = computed(() => userStore.userInfo?.email || '')

const form = reactive({
  receiverIds: '',
  ccIds: '',
  subject: '',
  body: ''
})

// 初始化：加载回复参数或草稿内容
onMounted(async () => {
  const draftIdParam = route.query.draftId

  if (draftIdParam) {
    // 加载已有草稿
    draftId.value = Number(draftIdParam)
    await loadDraft(draftId.value)
  } else {
    // 从路由 query 参数自动填充（用于回复功能）
    if (route.query.to) {
      form.receiverIds = route.query.to
    }
    if (route.query.subject) {
      form.subject = route.query.subject
    }
  }
})

async function loadDraft(id) {
  try {
    const res = await mailDetail(id)
    const mail = res.data
    if (mail && mail.status === 2) {
      form.receiverIds = mail.receiverIds || ''
      form.ccIds = mail.ccIds || ''
      form.subject = mail.subject || ''
      form.body = mail.body || ''
    } else {
      ElMessage.warning('无法加载此草稿')
      router.push('/compose')
    }
  } catch (e) {
    ElMessage.error('加载草稿失败')
    router.push('/compose')
  }
}

async function handleFileChange(file) {
  // 上传附件到服务器
  try {
    const res = await uploadAttachment(file.raw)
    uploadedIds.value.push(res.data.id)
    fileList.value.push(file)
  } catch (e) {
    ElMessage.error('附件上传失败')
  }
}

function handleFileRemove(file) {
  const idx = fileList.value.indexOf(file)
  if (idx > -1) {
    fileList.value.splice(idx, 1)
    uploadedIds.value.splice(idx, 1)
  }
}

async function handleSend() {
  if (!form.receiverIds.trim()) {
    ElMessage.warning('请输入收件人')
    return
  }
  if (!form.subject.trim()) {
    ElMessage.warning('请输入主题')
    return
  }
  if (!form.body.trim()) {
    ElMessage.warning('请输入正文')
    return
  }

  sending.value = true
  try {
    if (draftId.value) {
      // 先更新草稿，再发送
      await updateDraft(draftId.value, {
        receiverEmails: form.receiverIds,
        ccEmails: form.ccIds,
        subject: form.subject,
        body: form.body,
        attachmentIds: uploadedIds.value
      })
      await sendDraft(draftId.value)
      ElMessage.success('草稿已发送')
    } else {
      await sendMail({
        receiverEmails: form.receiverIds,
        ccEmails: form.ccIds,
        subject: form.subject,
        body: form.body,
        attachmentIds: uploadedIds.value
      })
      ElMessage.success('邮件发送成功')
    }
    router.push('/sent')
  } catch (e) {
    // 错误已在拦截器中处理
  } finally {
    sending.value = false
  }
}

async function saveDraft() {
  savingDraft.value = true
  try {
    if (draftId.value) {
      // 更新已有草稿
      const res = await updateDraft(draftId.value, {
        receiverEmails: form.receiverIds,
        ccEmails: form.ccIds,
        subject: form.subject,
        body: form.body,
        attachmentIds: uploadedIds.value
      })
      ElMessage.success('草稿已更新')
    } else {
      // 新建草稿
      const res = await saveDraftApi({
        receiverEmails: form.receiverIds,
        ccEmails: form.ccIds,
        subject: form.subject || '(无主题)',
        body: form.body || '',
        attachmentIds: uploadedIds.value
      })
      draftId.value = res.data.id
      ElMessage.success('草稿已保存')
      // 更新URL，使浏览器刷新后仍能编辑此草稿
      router.replace({ path: '/compose', query: { draftId: draftId.value } })
    }
  } catch (e) {
    // 错误已在拦截器中处理
  } finally {
    savingDraft.value = false
  }
}
</script>

<style scoped>
.compose-page {
  max-width: 900px;
}

.compose-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.compose-form {
  background: #fff;
}

.editor-wrapper {
  width: 100%;
}

.upload-tip {
  font-size: 12px;
  color: #909399;
  margin-left: 8px;
}

.file-list {
  margin-top: 8px;
}
</style>
