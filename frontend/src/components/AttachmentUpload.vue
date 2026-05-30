<template>
  <div class="attachment-upload">
    <el-upload
      ref="uploadRef"
      :auto-upload="false"
      :limit="maxFiles"
      :on-change="handleChange"
      :on-remove="handleRemove"
      :before-upload="beforeUpload"
      multiple
    >
      <el-button type="primary" plain :disabled="fileList.length >= maxFiles">
        <el-icon><Upload /></el-icon> 添加附件
      </el-button>
      <template #tip>
        <span class="el-upload__tip">
          支持任意格式文件，单个不超过 {{ maxSizeMB }}MB，最多 {{ maxFiles }} 个
        </span>
      </template>
    </el-upload>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'

const props = defineProps({
  maxFiles: { type: Number, default: 5 },
  maxSizeMB: { type: Number, default: 50 }
})

const emit = defineEmits(['file-change', 'file-remove'])

const uploadRef = ref()
const fileList = ref([])

function beforeUpload(file) {
  const maxSize = props.maxSizeMB * 1024 * 1024
  if (file.size > maxSize) {
    ElMessage.error(`文件大小不能超过 ${props.maxSizeMB}MB`)
    return false
  }
  return true
}

function handleChange(file) {
  fileList.value.push(file)
  emit('file-change', file)
}

function handleRemove(file) {
  const idx = fileList.value.indexOf(file)
  if (idx > -1) fileList.value.splice(idx, 1)
  emit('file-remove', file)
}
</script>
