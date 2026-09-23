<template>
  <div class="change-password-page">
    <h2>🔒 修改密码</h2>
    <el-alert
      v-if="forced"
      type="warning"
      show-icon
      :closable="false"
      title="请先修改密码"
      description="当前使用的是初始密码或管理员重置的临时密码，修改后才能继续使用其他功能。"
      style="margin-bottom: 20px"
    />
    <p v-else class="page-desc">修改成功后当前登录状态保持有效，无需重新登录。</p>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" class="password-form">
      <el-form-item label="当前密码" prop="oldPassword">
        <el-input v-model="form.oldPassword" type="password" placeholder="请输入当前密码" show-password />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="form.newPassword" type="password" placeholder="6-32 位" show-password />
      </el-form-item>
      <el-form-item label="确认新密码" prop="confirmPassword">
        <el-input v-model="form.confirmPassword" type="password" placeholder="再次输入新密码"
          show-password @keyup.enter="handleSubmit" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="handleSubmit">确认修改</el-button>
        <el-button v-if="!forced" @click="$router.back()">取消</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { changePassword } from '@/api/user'
import { ElMessage } from 'element-plus'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref()
const loading = ref(false)

/** 是否处于"必须改密"状态 —— 此时不提供取消按钮，改完才能离开 */
const forced = computed(() => userStore.userInfo?.mustChangePassword === true)

const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const validateConfirm = (rule, value, callback) => {
  if (value !== form.newPassword) {
    callback(new Error('两次输入的密码不一致'))
    return
  }
  callback()
}

const rules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度需为 6-32 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' }
  ]
}

async function handleSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await changePassword({
      oldPassword: form.oldPassword,
      newPassword: form.newPassword
    })
    // 后端已把 must_change_password 置 0，本地标记同步清掉，
    // 否则路由守卫会一直把用户弹回本页
    userStore.markPasswordChanged()
    ElMessage.success('密码修改成功')
    router.push('/')
  } catch (e) {
    // 错误提示由 request 拦截器统一弹出
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.change-password-page {
  max-width: 560px;
}

.page-desc {
  color: #909399;
  margin-bottom: 20px;
}

.password-form {
  margin-top: 12px;
}
</style>
