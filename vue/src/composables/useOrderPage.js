import { ElMessage, ElMessageBox } from 'element-plus'
import { ref } from 'vue'
import { approveOrder, deleteOrder, getOrder, listOrders, rejectOrder, submitOrder } from '../api/wms'
import { usePagedList } from './usePagedList'

/**
 * 六类单据共用的列表与动作：提交、审核、驳回、删除、查看详情。
 * 各页面的差异只在录入表单与详情字段，动作和列表行为保持一致。
 */
export function useOrderPage(kind, initialFilters = {}) {
  const list = usePagedList(
    (params) => listOrders(kind, params),
    { status: null, warehouseId: null, keyword: '', dateFrom: null, dateTo: null, ...initialFilters },
  )

  const detail = ref(null)
  const detailVisible = ref(false)
  const detailLoading = ref(false)
  const detailError = ref('')
  const actioning = ref(false)

  async function openDetail(row) {
    detailVisible.value = true
    detailLoading.value = true
    detailError.value = ''
    detail.value = null
    try {
      detail.value = await getOrder(kind, row.id)
    } catch (failure) {
      detailError.value = failure.message
    } finally {
      detailLoading.value = false
    }
  }

  async function submit(row) {
    const confirmed = await ElMessageBox.confirm(
      `提交后单据进入待审核状态，草稿将不能再修改。确认提交「${row.orderNo}」？`,
      '提交审核',
      { confirmButtonText: '提交', cancelButtonText: '取消' },
    ).catch(() => false)
    if (!confirmed) return false
    actioning.value = true
    try {
      await submitOrder(kind, row.id)
      ElMessage.success('已提交，等待审核')
      await list.load()
      return true
    } catch (failure) {
      ElMessage.error(failure.message)
      return false
    } finally {
      actioning.value = false
    }
  }

  async function approve(row) {
    const confirmed = await ElMessageBox.confirm(
      `审核通过后立即更新库存且不可撤销。确认审核「${row.orderNo}」？`,
      '审核生效',
      { type: 'warning', confirmButtonText: '审核通过', cancelButtonText: '取消' },
    ).catch(() => false)
    if (!confirmed) return false
    actioning.value = true
    try {
      await approveOrder(kind, row.id)
      ElMessage.success('审核通过，库存已更新')
      await list.load()
      if (detailVisible.value) await openDetail(row)
      return true
    } catch (failure) {
      ElMessage.error(failure.message)
      await list.load()
      return false
    } finally {
      actioning.value = false
    }
  }

  async function reject(row) {
    const input = await ElMessageBox.prompt('请填写驳回原因，创建人修改后可重新提交。', '驳回单据', {
      confirmButtonText: '驳回',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputValidator: (value) => (value && value.trim().length > 0 ? true : '请填写驳回原因'),
    }).catch(() => null)
    if (!input) return false
    actioning.value = true
    try {
      await rejectOrder(kind, row.id, input.value.trim())
      ElMessage.success('单据已驳回')
      await list.load()
      if (detailVisible.value) await openDetail(row)
      return true
    } catch (failure) {
      ElMessage.error(failure.message)
      return false
    } finally {
      actioning.value = false
    }
  }

  async function remove(row) {
    const confirmed = await ElMessageBox.confirm(
      `确认删除草稿「${row.orderNo}」？删除后不可恢复。`,
      '删除草稿',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    ).catch(() => false)
    if (!confirmed) return false
    actioning.value = true
    try {
      await deleteOrder(kind, row.id)
      ElMessage.success('草稿已删除')
      await list.load()
      return true
    } catch (failure) {
      ElMessage.error(failure.message)
      return false
    } finally {
      actioning.value = false
    }
  }

  return {
    ...list,
    detail,
    detailVisible,
    detailLoading,
    detailError,
    actioning,
    openDetail,
    submit,
    approve,
    reject,
    remove,
  }
}

/** 单据行的状态标签配置。 */
export const STATUS_TAGS = {
  0: { label: '草稿', type: 'info' },
  1: { label: '待审核', type: 'warning' },
  2: { label: '已生效', type: 'success' },
  3: { label: '已驳回', type: 'danger' },
}
