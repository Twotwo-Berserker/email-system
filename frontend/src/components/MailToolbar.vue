<template>
  <div class="mail-toolbar">
    <div class="toolbar-left">
      <el-button type="primary" @click="$router.push('/compose')">
        <el-icon><Edit /></el-icon> 写信
      </el-button>
      <el-button @click="$emit('refresh')">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
      <el-button type="danger" plain :disabled="!hasSelection" @click="$emit('batch-delete')">
        <el-icon><Delete /></el-icon> 批量删除
      </el-button>
    </div>
    <div class="toolbar-right">
      <el-input
        :model-value="keyword"
        placeholder="搜索邮件…"
        clearable
        :prefix-icon="Search"
        style="width: 260px"
        @update:model-value="$emit('update:keyword', $event)"
        @keyup.enter="$emit('search')"
        @clear="$emit('search')"
      />
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Search } from '@element-plus/icons-vue'

const props = defineProps({
  keyword: { type: String, default: '' },
  selectionCount: { type: Number, default: 0 }
})

defineEmits(['refresh', 'search', 'batch-delete', 'update:keyword'])

const hasSelection = computed(() => props.selectionCount > 0)
</script>

<style scoped>
.mail-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 12px;
  border-bottom: 1px solid #ebeef5;
  margin-bottom: 8px;
}

.toolbar-left {
  display: flex;
  gap: 8px;
}
</style>
