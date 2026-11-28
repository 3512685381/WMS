<script setup>
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { createWarehouse, deleteWarehouse, listWarehouses, updateWarehouse } from '../../../api/wms'
import { usePagedList } from '../../../composables/usePagedList'
import { useCan } from '../../../utils/auth'

const can = useCan()
const { filters, rows, total, page, size, loading, error, load, search, reset, changeSize } = usePagedList(
  listWarehouses,
  { keyword: '', status: null },
)

const dialogVisible = ref(false)
const editing = ref(null)
const submitting = ref(false)
const formRef = ref(null)
const form = reactive({ code: '', name: '', address: '', manager: '', status: 1 })
const rules = {
  code: [{ required: true, message: '请输入仓库编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入仓库名称', trigger: 'blur' }],
}

onMounted(load)

function openCreate() {
  editing.value = null
  Object.assign(form, { code: '', name: '', address: '', manager: '', status: 1 })
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  Object.assign(form, {
    code: row.code,
    name: row.name,
    address: row.address || '',
    manager: row.manager || '',
    status: row.status ?? 1,
  })
  dialogVisible.value = true
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  submitting.value = true
  try {
    const body = { ...form, address: form.address || null, manager: form.manager || null }
    if (editing.value) await updateWarehouse(editing.value.id, body)
    else await createWarehouse(body)
    ElMessage.success(editing.value ? '仓库已更新' : '仓库已创建')
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
    `确认删除仓库「${row.name}」？已被库区、库位或单据引用的仓库不能删除。`,
    '删除仓库',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  ).catch(() => false)
  if (!confirmed) return
  try {
    await deleteWarehouse(row.id)
    ElMessage.success('仓库已删除')
    await load()
  } catch (failure) {
    ElMessage.error(failure.message)
  }
}
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <h1>仓库</h1>
      <p>维护仓库档案。仓库被库区、库位或单据引用后不能删除，也不能修改编码。</p>
    </div>

    <div class="surface wms-toolbar">
      <el-input v-model="filters.keyword" placeholder="编码或名称" clearable @keyup.enter="search" />
      <el-select v-model="filters.status" placeholder="状态" clearable>
        <el-option label="启用" :value="1" />
        <el-option label="停用" :value="0" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <span class="wms-toolbar-spacer"></span>
      <el-button v-if="can('wms:warehouse:add')" type="primary" @click="openCreate">新增仓库</el-button>
    </div>

    <p v-if="error" class="wms-inline-error" role="alert">{{ error }}</p>

    <div class="surface wms-card">
      <el-table v-loading="loading" :data="rows" row-key="id" size="default">
        <el-table-column prop="code" label="编码" width="140" />
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column prop="manager" label="负责人" width="120">
          <template #default="{ row }">{{ row.manager || '—' }}</template>
        </el-table-column>
        <el-table-column prop="address" label="地址" min-width="200">
          <template #default="{ row }">{{ row.address || '—' }}</template>
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
            <el-button v-if="can('wms:warehouse:edit')" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="can('wms:warehouse:remove')" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="wms-empty">暂无仓库，先新增一个仓库再建立库区。</div></template>
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

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑仓库' : '新增仓库'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="wms-form-grid">
          <el-form-item label="仓库编码" prop="code">
            <el-input v-model="form.code" maxlength="32" placeholder="如 WH-SH-01" />
          </el-form-item>
          <el-form-item label="仓库名称" prop="name">
            <el-input v-model="form.name" maxlength="128" placeholder="如 上海中心仓" />
          </el-form-item>
          <el-form-item label="负责人">
            <el-input v-model="form.manager" maxlength="64" />
          </el-form-item>
          <el-form-item label="状态">
            <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
          </el-form-item>
          <el-form-item label="地址" class="wms-detail-wide">
            <el-input v-model="form.address" maxlength="255" />
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
