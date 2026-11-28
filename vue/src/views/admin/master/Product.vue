<script setup>
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { createProduct, deleteProduct, listProducts, updateProduct } from '../../../api/wms'
import { loadCategoryOptions, loadPartnerOptions } from '../../../api/options'
import { usePagedList } from '../../../composables/usePagedList'
import { useCan } from '../../../utils/auth'

const can = useCan()
const categories = ref([])
const suppliers = ref([])
const { filters, rows, total, page, size, loading, error, load, search, reset, changeSize } = usePagedList(
  listProducts,
  { categoryId: null, batchManaged: null, keyword: '' },
)

const dialogVisible = ref(false)
const editing = ref(null)
const submitting = ref(false)
const formRef = ref(null)
const blank = {
  skuCode: '',
  name: '',
  categoryId: null,
  spec: '',
  unit: '',
  batchManaged: 1,
  minStock: null,
  maxStock: null,
  shelfLifeDays: null,
  defaultSupplierId: null,
  referencePrice: null,
}
const form = reactive({ ...blank })
const rules = {
  skuCode: [{ required: true, message: '请输入 SKU 编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  unit: [{ required: true, message: '请输入计量单位', trigger: 'blur' }],
}

onMounted(async () => {
  await Promise.allSettled([
    loadCategoryOptions().then((data) => { categories.value = data }),
    loadPartnerOptions('supplier').then((data) => { suppliers.value = data }),
  ])
  await load()
})

function openCreate() {
  editing.value = null
  Object.assign(form, { ...blank })
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  Object.assign(form, {
    skuCode: row.skuCode,
    name: row.name,
    categoryId: row.categoryId ?? null,
    spec: row.spec || '',
    unit: row.unit,
    batchManaged: row.batchManaged ?? 1,
    minStock: row.minStock,
    maxStock: row.maxStock,
    shelfLifeDays: row.shelfLifeDays,
    defaultSupplierId: row.defaultSupplierId ?? null,
    referencePrice: row.referencePrice,
  })
  dialogVisible.value = true
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  if (form.minStock != null && form.maxStock != null && Number(form.maxStock) < Number(form.minStock)) {
    ElMessage.error('库存上限不能小于安全库存')
    return
  }
  submitting.value = true
  try {
    const body = {
      ...form,
      spec: form.spec || null,
      minStock: form.minStock === '' ? null : form.minStock,
      maxStock: form.maxStock === '' ? null : form.maxStock,
      shelfLifeDays: form.shelfLifeDays === '' ? null : form.shelfLifeDays,
      referencePrice: form.referencePrice === '' ? null : form.referencePrice,
      categoryId: form.categoryId ?? null,
      defaultSupplierId: form.defaultSupplierId ?? null,
    }
    if (editing.value) await updateProduct(editing.value.id, body)
    else await createProduct(body)
    ElMessage.success(editing.value ? '商品已更新' : '商品已创建')
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
    `确认删除商品「${row.name}」？已有库存或单据引用时不允许删除。`,
    '删除商品',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  ).catch(() => false)
  if (!confirmed) return
  try {
    await deleteProduct(row.id)
    ElMessage.success('商品已删除')
    await load()
  } catch (failure) {
    ElMessage.error(failure.message)
  }
}
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <h1>商品档案</h1>
      <p>SKU 唯一；已产生库存后不能再修改 SKU、批次管理方式与计量单位。</p>
    </div>

    <div class="surface wms-toolbar">
      <el-input v-model="filters.keyword" placeholder="SKU 或商品名称" clearable @keyup.enter="search" />
      <el-select v-model="filters.categoryId" placeholder="分类" clearable filterable>
        <el-option v-for="item in categories" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-select v-model="filters.batchManaged" placeholder="批次管理" clearable>
        <el-option label="按批次" :value="1" />
        <el-option label="不按批次" :value="0" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <span class="wms-toolbar-spacer"></span>
      <el-button v-if="can('wms:product:add')" type="primary" @click="openCreate">新增商品</el-button>
    </div>

    <p v-if="error" class="wms-inline-error" role="alert">{{ error }}</p>

    <div class="surface wms-card">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="skuCode" label="SKU" width="140" />
        <el-table-column prop="name" label="商品名称" min-width="170" />
        <el-table-column prop="spec" label="规格" min-width="120">
          <template #default="{ row }">{{ row.spec || '—' }}</template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="80" />
        <el-table-column label="批次" width="96">
          <template #default="{ row }">
            <el-tag :type="row.batchManaged === 1 ? 'warning' : 'info'" size="small" effect="plain">
              {{ row.batchManaged === 1 ? '按批次' : '不分批' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="安全/上限" width="130" align="right">
          <template #default="{ row }">
            <span class="wms-num">{{ row.minStock ?? '—' }} / {{ row.maxStock ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="保质期" width="100" align="right">
          <template #default="{ row }">
            <span class="wms-num">{{ row.shelfLifeDays ? row.shelfLifeDays + ' 天' : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="categoryName" label="分类" width="130">
          <template #default="{ row }">{{ row.categoryName || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="right">
          <template #default="{ row }">
            <el-button v-if="can('wms:product:edit')" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="can('wms:product:remove')" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="wms-empty">没有符合条件的商品。</div></template>
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

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑商品' : '新增商品'" width="680px">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="wms-form-grid">
          <el-form-item label="SKU 编码" prop="skuCode">
            <el-input v-model="form.skuCode" maxlength="64" placeholder="如 CHG-20W" />
          </el-form-item>
          <el-form-item label="商品名称" prop="name">
            <el-input v-model="form.name" maxlength="128" />
          </el-form-item>
          <el-form-item label="分类">
            <el-select v-model="form.categoryId" clearable filterable>
              <el-option v-for="item in categories" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="规格">
            <el-input v-model="form.spec" maxlength="128" />
          </el-form-item>
          <el-form-item label="计量单位" prop="unit">
            <el-input v-model="form.unit" maxlength="16" placeholder="如 件 / 箱" />
          </el-form-item>
          <el-form-item label="批次管理">
            <el-select v-model="form.batchManaged">
              <el-option label="按批次管理" :value="1" />
              <el-option label="不按批次" :value="0" />
            </el-select>
          </el-form-item>
          <el-form-item label="安全库存">
            <el-input v-model="form.minStock" placeholder="低库存预警阈值" />
          </el-form-item>
          <el-form-item label="库存上限">
            <el-input v-model="form.maxStock" placeholder="超储预警阈值" />
          </el-form-item>
          <el-form-item label="保质期（天）">
            <el-input v-model="form.shelfLifeDays" placeholder="入库时用于推算效期" />
          </el-form-item>
          <el-form-item label="默认供应商">
            <el-select v-model="form.defaultSupplierId" clearable filterable>
              <el-option v-for="item in suppliers" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="参考价">
            <el-input v-model="form.referencePrice" placeholder="最多四位小数" />
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
