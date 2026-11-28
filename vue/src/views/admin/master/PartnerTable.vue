<script setup>
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { createPartner, deletePartner, listPartners, updatePartner } from '../../../api/wms'
import { usePagedList } from '../../../composables/usePagedList'
import { useCan } from '../../../utils/auth'

const props = defineProps({
  type: { type: String, required: true },
  title: { type: String, required: true },
  description: { type: String, default: '' },
  permissionKey: { type: String, required: true },
})

const can = useCan()
const label = computed(() => (props.type === 'customer' ? '客户' : '供应商'))
const { filters, rows, total, page, size, loading, error, load, search, reset, changeSize } = usePagedList(
  (params) => listPartners(props.type, params),
  { keyword: '' },
)

const dialogVisible = ref(false)
const editing = ref(null)
const submitting = ref(false)
const formRef = ref(null)
const blank = { code: '', name: '', contact: '', phone: '', address: '' }
const form = reactive({ ...blank })
const rules = {
  code: [{ required: true, message: '请输入编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
}

onMounted(load)

function openCreate() {
  editing.value = null
  Object.assign(form, { ...blank })
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  Object.assign(form, {
    code: row.code,
    name: row.name,
    contact: row.contact || '',
    phone: row.phone || '',
    address: row.address || '',
  })
  dialogVisible.value = true
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  submitting.value = true
  try {
    const body = {
      ...form,
      contact: form.contact || null,
      phone: form.phone || null,
      address: form.address || null,
    }
    if (editing.value) await updatePartner(props.type, editing.value.id, body)
    else await createPartner(props.type, body)
    ElMessage.success(editing.value ? `${label.value}已更新` : `${label.value}已创建`)
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
    `确认删除${label.value}「${row.name}」？已被单据引用时不允许删除。`,
    `删除${label.value}`,
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  ).catch(() => false)
  if (!confirmed) return
  try {
    await deletePartner(props.type, row.id)
    ElMessage.success(`${label.value}已删除`)
    await load()
  } catch (failure) {
    ElMessage.error(failure.message)
  }
}
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <h1>{{ title }}</h1>
      <p>{{ description }}</p>
    </div>

    <div class="surface wms-toolbar">
      <el-input v-model="filters.keyword" placeholder="编码 / 名称" clearable @keyup.enter="search" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <span class="wms-toolbar-spacer"></span>
      <el-button v-if="can(`wms:${permissionKey}:add`)" type="primary" @click="openCreate">新增{{ label }}</el-button>
    </div>

    <p v-if="error" class="wms-inline-error" role="alert">{{ error }}</p>

    <div class="surface wms-card">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="code" label="编码" width="150" />
        <el-table-column prop="name" label="名称" min-width="190" />
        <el-table-column prop="contact" label="联系人" width="120">
          <template #default="{ row }">{{ row.contact || '—' }}</template>
        </el-table-column>
        <el-table-column prop="phone" label="电话" width="150">
          <template #default="{ row }">{{ row.phone || '—' }}</template>
        </el-table-column>
        <el-table-column prop="address" label="地址" min-width="200">
          <template #default="{ row }">{{ row.address || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="right">
          <template #default="{ row }">
            <el-button v-if="can(`wms:${permissionKey}:edit`)" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="can(`wms:${permissionKey}:remove`)" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="wms-empty">没有符合条件的{{ label }}。</div></template>
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

    <el-dialog v-model="dialogVisible" :title="`${editing ? '编辑' : '新增'}${label}`" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="wms-form-grid">
          <el-form-item label="编码" prop="code">
            <el-input v-model="form.code" maxlength="32" />
          </el-form-item>
          <el-form-item label="名称" prop="name">
            <el-input v-model="form.name" maxlength="128" />
          </el-form-item>
          <el-form-item label="联系人">
            <el-input v-model="form.contact" maxlength="64" />
          </el-form-item>
          <el-form-item label="电话">
            <el-input v-model="form.phone" maxlength="32" />
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
