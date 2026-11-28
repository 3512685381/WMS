<script setup>
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { createCategory, deleteCategory, getCategoryTree, updateCategory } from '../../../api/wms'
import { useCan } from '../../../utils/auth'

const can = useCan()
const tree = ref([])
const loading = ref(false)
const error = ref('')

const dialogVisible = ref(false)
const editing = ref(null)
const submitting = ref(false)
const formRef = ref(null)
const form = reactive({ parentId: null, name: '', sortOrder: 0 })
const rules = { name: [{ required: true, message: '请输入分类名称', trigger: 'blur' }] }

const parentOptions = computed(() => {
  const options = [{ id: null, label: '（作为一级分类）' }]
  const walk = (nodes, depth) => {
    for (const node of nodes || []) {
      if (editing.value && node.id === editing.value.id) continue
      options.push({ id: node.id, label: `${'　'.repeat(depth)}${node.name}` })
      walk(node.children, depth + 1)
    }
  }
  walk(tree.value, 0)
  return options
})

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    tree.value = await getCategoryTree()
  } catch (failure) {
    error.value = failure.message
    tree.value = []
  } finally {
    loading.value = false
  }
}

function openCreate(parent) {
  editing.value = null
  Object.assign(form, { parentId: parent?.id ?? null, name: '', sortOrder: 0 })
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  Object.assign(form, { parentId: row.parentId ?? null, name: row.name, sortOrder: row.sortOrder ?? 0 })
  dialogVisible.value = true
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  submitting.value = true
  try {
    const body = { parentId: form.parentId, name: form.name, sortOrder: Number(form.sortOrder) || 0 }
    if (editing.value) await updateCategory(editing.value.id, body)
    else await createCategory(body)
    ElMessage.success(editing.value ? '分类已更新' : '分类已创建')
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
    `确认删除分类「${row.name}」？存在子分类或商品引用时不允许删除。`,
    '删除分类',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  ).catch(() => false)
  if (!confirmed) return
  try {
    await deleteCategory(row.id)
    ElMessage.success('分类已删除')
    await load()
  } catch (failure) {
    ElMessage.error(failure.message)
  }
}
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <h1>商品分类</h1>
      <p>分类最多三级；上级不能选择自身或自己的下级，存在子分类或商品引用时不能删除。</p>
    </div>

    <div class="surface wms-toolbar">
      <el-button type="primary" @click="load">刷新</el-button>
      <span class="wms-toolbar-spacer"></span>
      <el-button v-if="can('wms:category:add')" type="primary" @click="openCreate(null)">新增一级分类</el-button>
    </div>

    <p v-if="error" class="wms-inline-error" role="alert">{{ error }}</p>

    <div class="surface wms-card">
      <el-table v-loading="loading" :data="tree" row-key="id" default-expand-all :tree-props="{ children: 'children' }">
        <el-table-column prop="name" label="分类名称" min-width="260" />
        <el-table-column label="排序" width="100" align="right">
          <template #default="{ row }"><span class="wms-num">{{ row.sortOrder ?? 0 }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="230" align="right">
          <template #default="{ row }">
            <el-button v-if="can('wms:category:add')" link type="primary" @click="openCreate(row)">新增下级</el-button>
            <el-button v-if="can('wms:category:edit')" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="can('wms:category:remove')" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="wms-empty">还没有分类。</div></template>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑分类' : '新增分类'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="上级分类">
          <el-select v-model="form.parentId" clearable placeholder="不选表示一级分类">
            <el-option v-for="item in parentOptions" :key="String(item.id)" :label="item.label" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="分类名称" prop="name">
          <el-input v-model="form.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input v-model="form.sortOrder" placeholder="数字越小越靠前" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>
