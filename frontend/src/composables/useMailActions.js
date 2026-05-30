import { ElMessage, ElMessageBox } from 'element-plus'
import { permanentDeleteMail } from '@/api/mail'

/**
 * 邮件操作相关的可复用逻辑
 */
export function useMailActions() {
  /**
   * 彻底删除邮件（含确认对话框）
   * 返回 true 表示已删除，false 表示用户取消
   */
  async function permanentDeleteWithConfirm(mailId) {
    try {
      await ElMessageBox.confirm(
        '彻底删除后将无法恢复，确定要继续吗？',
        '确认删除',
        {
          confirmButtonText: '确定删除',
          cancelButtonText: '取消',
          type: 'warning'
        }
      )
    } catch {
      // 用户取消或关闭对话框
      return false
    }

    await permanentDeleteMail(mailId)
    ElMessage.success('已彻底删除')
    return true
  }

  return { permanentDeleteWithConfirm }
}
