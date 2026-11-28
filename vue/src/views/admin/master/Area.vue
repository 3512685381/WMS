<script setup>
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { AREA_TYPES, createArea, deleteArea, listAreas, updateArea } from '../../../api/wms'
import { loadWarehouseOptions } from '../../../api/options'
import { useCan } from '../../../utils/auth'

const can = useCan()
const warehouses = ref([])
const selectedWarehouse = ref(null)
const rows = ref([])
const loading = ref(false)
const error = ref('')

const dialogVisible = ref(false)
const editing = ref(null)
const submitting = ref(false)
const formRef = ref(null)
const form = reactive({ warehouseId: null, code: '', name: '', areaType: 'STORAGE' })
const rules = {
  warehouseId: [{ required: true, message: '请选择所属仓库', trigger: 'change' }],
  code: [{ required: true, message: '请输入库区编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入库区名称', trigger: 'blur' }],
  areaType: [{ required: true, message: '请选择库区类型', trigger: 'change' }],
}

onMounted(async () => {
  try {
    warehouses.value = await loadWarehouseOptions()
    if (warehouses.value.length) {
      selectedWarehouse.value = warehouses.value[0].id
      await load()
    }
  } catch (failure) {
    error.value = failure.message
  }
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = await listAreas({ warehouseId: selectedWarehouse.value })
  } catch (failure) {
    error.value = failure.message
    rows.value = []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, {
    warehouseId: selectedWarehouse.value,
    code: '',
    name: '',
    areaType: 'STORAGE',
  })
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  Object.assign(form, {
    warehouseId: row.warehouseId,
    code: row.code,
    name: row.name,
    areaType: row.areaType,
  })
  dialogVisible.value = true
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  submitting.value = true
  try {
    if (editing.value) await updateArea(editing.value.id, { ...form })
    else await createArea({ ...form })
    ElMessage.success(editing.value ? '库区已更新' : '库区已创建')
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
    `确认删除库区「${row.name}」？库区下还有库位时不允许删除。`,
    '删除库区',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  ).catch(() => false)
  if (!confirmed) return
  try {
    await deleteArea(row.id)
    ElMessage.success('库区已删除')
    await load()
  } catch (failure) {
    ElMessage.error(failure.message)
  }
}
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <h1>库区</h1>
      <p>库区挂在仓库下，库位再挂在库区下；有库位的库区不能删除，也不能改所属仓库。</p>
    </div>

    <div class="surface wms-toolbar">
      <el-select v-model="selectedWarehouse" placeholder="选择仓库" class="wms-toolbar-wide" @change="load">
        <el-option v-for="item in warehouses" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" />
      </el-select>
      <el-button type="primary" @click="load">刷新</el-button>
      <span class="wms-toolbar-spacer"></span>
      <el-button v-if="can('wms:area:add')" type="primary" :disabled="!selectedWarehouse" @click="openCreate">
        新增库区
      </el-button>
    </div>

    <p v-if="error" class="wms-inline-error" role="alert">{{ error }}</p>

    <div class="surface wms-card">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="code" label="库区编码" width="150" />
        <el-table-column prop="name" label="库区名称" min-width="180" />
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ AREA_TYPES[row.areaType] || row.areaType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="warehouseName" label="所属仓库" min-width="160" />
        <el-table-column label="操作" width="150" align="right">
          <template #default="{ row }">
            <el-button v-if="can('wms:area:edit')" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="can('wms:area:remove')" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="wms-empty">该仓库还没有库区。</div></template>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑库区' : '新增库区'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="wms-form-grid">
          <el-form-item label="所属仓库" prop="warehouseId">
            <el-select v-model="form.warehouseId" :disabled="Boolean(editing)">
              <el-option v-for="item in warehouses" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="库区类型" prop="areaType">
            <el-select v-model="form.areaType">
              <el-option v-for="(label, key) in AREA_TYPES" :key="key" :label="label" :value="key" />
            </el-select>
          </el-form-item>
          <el-form-item label="库区编码" prop="code">
            <el-input v-model="form.code" maxlength="32" placeholder="如 STORAGE-A" />
          </el-form-item>
          <el-form-item label="库区名称" prop="name">
            <el-input v-model="form.name" maxlength="128" placeholder="如 A 区存储" />
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
