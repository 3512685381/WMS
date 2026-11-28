import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'

/**
 * 分页列表的通用状态：筛选条件、分页、加载与错误。
 * loader 接收 { ...filters, page, size }，返回 PageResult。
 */
export function usePagedList(loader, initialFilters = {}, pageSize = 20) {
  const filters = reactive({ ...initialFilters })
  const rows = ref([])
  const total = ref(0)
  const page = ref(1)
  const size = ref(pageSize)
  const loading = ref(false)
  const error = ref('')

  async function load(target) {
    const next = target || page.value
    loading.value = true
    error.value = ''
    try {
      const data = await loader({ ...filters, page: next, size: size.value })
      rows.value = data?.records || []
      total.value = Number(data?.total || 0)
      page.value = Number(data?.page || next)
    } catch (failure) {
      error.value = failure.message
      rows.value = []
      total.value = 0
    } finally {
      loading.value = false
    }
  }

  function search() {
    return load(1)
  }

  function changeSize(nextSize) {
    size.value = nextSize
    return load(1)
  }

  function reset() {
    for (const key of Object.keys(filters)) {
      filters[key] = initialFilters[key] ?? null
    }
    return load(1)
  }

  return { filters, rows, total, page, size, loading, error, load, search, reset, changeSize }
}

/** 统一处理接口错误提示，避免每个页面重复 try/catch。 */
export async function runAction(action, successMessage, onDone) {
  try {
    await action()
    if (successMessage) ElMessage.success(successMessage)
    if (onDone) await onDone()
    return true
  } catch (failure) {
    ElMessage.error(failure.message || '操作未完成')
    return false
  }
}
