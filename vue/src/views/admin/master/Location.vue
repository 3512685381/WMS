<script setup>
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref, watch } from 'vue'
import { createLocation, deleteLocation, listLocations, updateLocation } from '../../../api/wms'
import { loadAreaOptions, loadWarehouseOptions } from '../../../api/options'
import { usePagedList } from '../../../composables/usePagedList'
import { useCan } from '../../../utils/auth'

const can = useCan()
const warehouses = ref([])
const { filters, rows, total, page, size, loading, error, load, search, reset, changeSize } = usePagedList(
  listLocations,
  { warehouseId: null, areaId: null, status: null, keyword: '' },
)

const filterAreas = ref([])

const dialogVisible = ref(false)
const editing = ref(null)
const submitting = ref(false)
const formRef = ref(null)
const form = reactive({ warehouseId: null, areaId: null, code: '', maxCapacity: null, status: 1 })
const formAreas = ref([])
const rules = {
  warehouseId: [{ required: true, message: '请选择所属仓库', trigger: 'change' }],
  areaId: [{ required: true, message: '请选择所属库区', trigger: 'change' }],
  code: [{ required: true, message: '请输入库位编码', trigger: 'blur' }],
}

onMounted(async () => {
  try {
    warehouses.value = await loadWarehouseOptions()
  } catch (failure) {
    error.value = failure.message
  }
  await load()
})

watch(() => filters.warehouseId, async (warehouseId) => {
  filters.areaId = null
  filterAreas.value = warehouseId ? await loadAreaOptions(warehouseId).catch(() => []) : []
})

watch(() => form.warehouseId, async (warehouseId) => {
  formAreas.value = warehouseId ? await loadAreaOptions(warehouseId).catch(() => []) : []
  if (!editing.value) form.areaId = null
  else if (!formAreas.value.some((area) => area.id === form.areaId)) form.areaId = null
})

function openCreate() {
  editing.value = null
  Object.assign(form, {
    warehouseId: filters.warehouseId || warehouses.value[0]?.id || null,
    areaId: null,
    code: '',
    maxCapacity: null,
    status: 1,
  })
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  Object.assign(form, {
    warehouseId: row.warehouseId,
    areaId: row.areaId,
    code: row.code,
    maxCapacity: row.maxCapacity,
    status: row.status ?? 1,
  })
  dialogVisible.value = true
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  submitting.value = true
  try {
    const body = {
      warehouseId: form.warehouseId,
      areaId: form.areaId,
      code: form.code,
      maxCapacity: form.maxCapacity,
      status: form.status,
    }
    if (editing.value) await updateLocation(editing.value.id, body)
    else await createLocation(body)
    ElMessage.success(editing.value ? '库位已更新' : '库位已创建')
    dialogVisible.value = false
    await load()
  } catch (failure) {
    ElMessage.error(failure.message)
  } finally {
    submitting.value = false
  }
}

async function remove(row) {
  const confirmed = await ElMessageBox.confirm(
    `确认删除库位「${row.code}」？库位上还有库存时不允许删除。`,
    '删除库位',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  ).catch(() => false)
  if (!confirmed) return
  try {
    await deleteLocation(row.id)
    ElMessage.success('库位已删除')
    await load()
  } catch (failure) {
    ElMessage.error(failure.message)
  }
}
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <h1>库位</h1>
      <p>库位是最小存储单元，库存按「仓库 + 库位 + 商品 + 批次」记账；有库存的库位不能换仓库。</p>
    </div>

    <div class="surface wms-toolbar">
      <el-select v-model="filters.warehouseId" placeholder="仓库" clearable>
        <el-option v-for="item in warehouses" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-select v-model="filters.areaId" placeholder="库区" clearable :disabled="!filters.warehouseId">
        <el-option v-for="item in filterAreas" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-input v-model="filters.keyword" placeholder="库位编码" clearable @keyup.enter="search" />
      <el-select v-model="filters.status" placeholder="状态" clearable>
        <el-option label="启用" :value="1" />
        <el-option label="停用" :value="0" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <span class="wms-toolbar-spacer"></span>
      <el-button v-if="can('wms:location:add')" type="primary" @click="openCreate">新增库位</el-button>
    </div>

    <p v-if="error" class="wms-inline-error" role="alert">{{ error }}</p>

    <div class="surface wms-card">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="code" label="库位编码" width="150" />
        <el-table-column prop="warehouseName" label="仓库" min-width="150" />
        <el-table-column prop="areaName" label="库区" min-width="140" />
        <el-table-column label="容量上限" width="120" align="right">
          <template #default="{ row }">
            <span class="wms-num">{{ row.maxCapacity ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="right">
          <template #default="{ row }">
            <el-button v-if="can('wms:location:edit')" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="can('wms:location:remove')" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="wms-empty">没有符合条件的库位。</div></template>
      </el-table>
      <div class="wms-pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="changeSize"
        />
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑库位' : '新增库位'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="wms-form-grid">
          <el-form-item label="所属仓库" prop="warehouseId">
            <el-select v-model="form.warehouseId">
              <el-option v-for="item in warehouses" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="所属库区" prop="areaId">
            <el-select v-model="form.areaId" :disabled="!form.warehouseId">
              <el-option v-for="item in formAreas" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="库位编码" prop="code">
            <el-input v-model="form.code" maxlength="32" placeholder="如 STO-A01" />
          </el-form-item>
          <el-form-item label="容量上限">
            <el-input v-model="form.maxCapacity" placeholder="留空表示不限制" />
          </el-form-item>
          <el-form-item label="状态">
            <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>
