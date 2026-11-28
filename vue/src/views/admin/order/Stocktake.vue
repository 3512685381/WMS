<script setup>
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { completeStocktake, countStocktake, createOrder, getOrder } from '../../../api/wms'
import { loadAreaOptions, loadWarehouseOptions } from '../../../api/options'
import { STATUS_TAGS, useOrderPage } from '../../../composables/useOrderPage'
import { useCan } from '../../../utils/auth'
import { formatDateTime } from '../../../utils/format'

const can = useCan()
const {
  filters, rows, total, page: currentPage, size, loading, error, load, search, reset, changeSize,
  detail, detailVisible, detailLoading, detailError, openDetail,
} = useOrderPage('stocktake')

const warehouses = ref([])
const areas = ref([])

const dialogVisible = ref(false)
const submitting = ref(false)
const formError = ref('')
const form = reactive({ warehouseId: null, areaId: null, remark: '' })

const countVisible = ref(false)
const countOrder = ref(null)
const countRows = ref([])
const countSaving = ref(false)
const countError = ref('')

const diffCount = computed(() => countRows.value.filter((row) => row.actualQty !== null && row.actualQty !== ''
  && Number(row.actualQty) !== Number(row.bookQty)).length)

onMounted(async () => {
  try {
    warehouses.value = await loadWarehouseOptions()
  } catch (failure) {
    error.value = failure.message
  }
  await load()
})

async function onWarehouseChange() {
  form.areaId = null
  areas.value = form.warehouseId ? await loadAreaOptions(form.warehouseId).catch(() => []) : []
}

function openCreate() {
  formError.value = ''
  Object.assign(form, { warehouseId: warehouses.value[0]?.id ?? null, areaId: null, remark: '' })
  areas.value = []
  if (form.warehouseId) onWarehouseChange()
  dialogVisible.value = true
}

async function save() {
  if (!form.warehouseId) {
    formError.value = '请选择仓库'
    return
  }
  submitting.value = true
  try {
    const created = await createOrder('stocktake', {
      warehouseId: form.warehouseId,
      areaId: form.areaId ?? null,
      remark: form.remark || null,
    })
    ElMessage.success(`盘点单 ${created.orderNo} 已创建，账面数量已快照`)
    dialogVisible.value = false
    await load()
  } catch (failure) {
    formError.value = failure.message
  } finally {
    submitting.value = false
  }
}

async function openCount(row) {
  countError.value = ''
  countOrder.value = row
  countVisible.value = true
  try {
    const data = await getOrder('stocktake', row.id)
    countRows.value = data.items.map((item) => ({
      itemId: item.id,
      skuCode: item.skuCode,
      productName: item.productName,
      locationCode: item.locationCode,
      batchNo: item.batchNo,
      bookQty: item.bookQty,
      actualQty: item.actualQty === null ? '' : String(item.actualQty),
    }))
  } catch (failure) {
    countError.value = failure.message
    countRows.value = []
  }
}

async function saveCount() {
  const items = countRows.value
    .filter((row) => row.actualQty !== '' && row.actualQty !== null)
    .map((row) => ({ itemId: row.itemId, actualQty: row.actualQty }))
  if (!items.length) {
    countError.value = '请至少录入一条实盘数量'
    return
  }
  countSaving.value = true
  countError.value = ''
  try {
    await countStocktake(countOrder.value.id, items)
    ElMessage.success(`已保存 ${items.length} 条盘点结果`)
    await load()
  } catch (failure) {
    countError.value = failure.message
  } finally {
    countSaving.value = false
  }
}

async function complete() {
  const confirmed = await ElMessageBox.confirm(
    '完结盘点后不能再修改实盘数量；有差异时会自动生成一张待审核的调整单。确认完结？',
    '完结盘点',
    { type: 'warning', confirmButtonText: '完结', cancelButtonText: '取消' },
  ).catch(() => false)
  if (!confirmed) return
  try {
    const result = await completeStocktake(countOrder.value.id)
    const adjustId = Number(result?.adjustOrderId || 0)
    ElMessage.success(adjustId ? `盘点已完结，已生成调整单 #${adjustId}，请在调整单页面审核` : '盘点已完结，没有差异')
    countVisible.value = false
    await load()
  } catch (failure) {
    countError.value = failure.message
  }
}
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <h1>库存盘点单</h1>
      <p>建单时快照账面数量与库存版本；录入实盘后完结，差异会生成调整单，库存在调整单审核时才变更。</p>
    </div>

    <div class="surface wms-toolbar">
      <el-select v-model="filters.status" placeholder="状态" clearable>
        <el-option v-for="(item, key) in STATUS_TAGS" :key="key" :label="item.label" :value="Number(key)" />
      </el-select>
      <el-select v-model="filters.warehouseId" placeholder="仓库" clearable>
        <el-option v-for="item in warehouses" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-input v-model="filters.keyword" placeholder="单号 / 备注" clearable @keyup.enter="search" />
      <el-date-picker v-model="filters.dateFrom" type="date" placeholder="起始日期" value-format="YYYY-MM-DD" style="width: 150px" />
      <el-date-picker v-model="filters.dateTo" type="date" placeholder="截止日期" value-format="YYYY-MM-DD" style="width: 150px" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <span class="wms-toolbar-spacer"></span>
      <el-button v-if="can('wms:stocktake:add')" type="primary" @click="openCreate">新建盘点单</el-button>
    </div>

    <p v-if="error" class="wms-inline-error" role="alert">{{ error }}</p>

    <div class="surface wms-card">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="orderNo" label="单号" width="150" />
        <el-table-column prop="warehouseName" label="仓库" width="130" />
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAGS[row.status]?.type || 'info'" size="small">
              {{ row.statusText || STATUS_TAGS[row.status]?.label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="明细" width="80" align="right">
          <template #default="{ row }"><span class="wms-num">{{ row.itemCount }}</span></template>
        </el-table-column>
        <el-table-column label="已录入" width="90" align="right">
          <template #default="{ row }"><span class="wms-num">{{ row.countedCount }}</span></template>
        </el-table-column>
        <el-table-column label="差异行" width="90" align="right">
          <template #default="{ row }"><span class="wms-num">{{ row.diffCount }}</span></template>
        </el-table-column>
        <el-table-column label="差异合计" width="110" align="right">
          <template #default="{ row }"><span class="wms-num">{{ row.totalDiff }}</span></template>
        </el-table-column>
        <el-table-column label="创建时间" width="165">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="right" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="can('wms:stocktake:count') && row.status === 0"
              link
              type="primary"
              @click="openCount(row)"
            >录入盘点</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="wms-empty">还没有盘点单。</div></template>
      </el-table>
      <div class="wms-pager">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="changeSize"
        />
      </div>
    </div>

    <el-dialog v-model="dialogVisible" title="新建盘点单" width="560px">
      <p v-if="formError" class="wms-inline-error" role="alert">{{ formError }}</p>
      <el-form label-position="top">
        <div class="wms-form-grid">
          <el-form-item label="仓库" required>
            <el-select v-model="form.warehouseId" @change="onWarehouseChange">
              <el-option v-for="item in warehouses" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="库区">
            <el-select v-model="form.areaId" clearable placeholder="不选表示全仓">
              <el-option v-for="item in areas" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="备注" class="wms-detail-wide">
            <el-input v-model="form.remark" maxlength="500" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="save">创建并快照账面</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="countVisible" size="66%" :title="countOrder ? `录入盘点 ${countOrder.orderNo}` : '录入盘点'">
      <p v-if="countError" class="wms-inline-error" role="alert">{{ countError }}</p>
      <p class="wms-hint">
        账面数量在建单时已快照，录入实盘后保存；差异行会显示差异数量。全部录入后再完结，系统会生成调整单。
      </p>
      <el-table :data="countRows" size="small" row-key="itemId" max-height="520">
        <el-table-column prop="locationCode" label="库位" width="110" />
        <el-table-column prop="skuCode" label="SKU" width="120" />
        <el-table-column prop="productName" label="商品" min-width="150" />
        <el-table-column prop="batchNo" label="批次" width="120" />
        <el-table-column label="账面" width="100" align="right">
          <template #default="{ row }"><span class="wms-num">{{ row.bookQty }}</span></template>
        </el-table-column>
        <el-table-column label="实盘" width="140">
          <template #default="{ row }">
            <el-input v-model="row.actualQty" size="small" placeholder="实盘数量" />
          </template>
        </el-table-column>
        <el-table-column label="差异" width="100" align="right">
          <template #default="{ row }">
            <span
              class="wms-num"
              :class="{ 'wms-danger': row.actualQty !== '' && Number(row.actualQty) !== Number(row.bookQty) }"
            >
              {{ row.actualQty === '' || row.actualQty === null ? '—' : (Number(row.actualQty) - Number(row.bookQty)).toFixed(3) }}
            </span>
          </template>
        </el-table-column>
        <template #empty><div class="wms-empty">该盘点单没有明细。</div></template>
      </el-table>
      <div class="wms-summary">
        <span>明细 <strong>{{ countRows.length }}</strong></span>
        <span>差异行 <strong>{{ diffCount }}</strong></span>
      </div>
      <template #footer>
        <el-button @click="countVisible = false">关闭</el-button>
        <el-button :loading="countSaving" @click="saveCount">保存盘点结果</el-button>
        <el-button v-if="can('wms:stocktake:complete')" type="primary" @click="complete">完结盘点</el-button>
      </template>
    </el-drawer>

    <el-drawer v-model="detailVisible" size="58%" :title="detail ? `盘点单 ${detail.order.orderNo}` : '盘点单详情'">
      <p v-if="detailError" class="wms-inline-error" role="alert">{{ detailError }}</p>
      <div v-if="detail" v-loading="detailLoading">
        <div class="wms-detail">
          <div><span>状态</span><strong>{{ detail.order.statusText }}</strong></div>
          <div><span>仓库</span><strong>{{ detail.order.warehouseName }}</strong></div>
          <div><span>创建人</span><strong>{{ detail.order.creatorName }}</strong></div>
          <div><span>创建时间</span><strong>{{ formatDateTime(detail.order.createdAt) }}</strong></div>
          <div><span>完结时间</span><strong>{{ formatDateTime(detail.order.postedAt) }}</strong></div>
          <div><span>明细 / 已录入 / 差异</span><strong>{{ detail.order.itemCount }} / {{ detail.order.countedCount }} / {{ detail.order.diffCount }}</strong></div>
          <div v-if="detail.order.remark" class="wms-detail-wide">
            <span>备注</span><strong>{{ detail.order.remark }}</strong>
          </div>
        </div>
        <el-table :data="detail.items" size="small" row-key="id">
          <el-table-column prop="locationCode" label="库位" width="110" />
          <el-table-column prop="skuCode" label="SKU" width="120" />
          <el-table-column prop="productName" label="商品" min-width="150" />
          <el-table-column prop="batchNo" label="批次" width="120" />
          <el-table-column label="账面" width="100" align="right">
            <template #default="{ row }"><span class="wms-num">{{ row.bookQty }}</span></template>
          </el-table-column>
          <el-table-column label="实盘" width="100" align="right">
            <template #default="{ row }"><span class="wms-num">{{ row.actualQty ?? '—' }}</span></template>
          </el-table-column>
          <el-table-column label="差异" width="100" align="right">
            <template #default="{ row }">
              <span class="wms-num" :class="{ 'wms-danger': Number(row.diffQty) !== 0 }">{{ row.diffQty ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="库存版本" width="100" align="right">
            <template #default="{ row }"><span class="wms-num">{{ row.inventoryVersion }}</span></template>
          </el-table-column>
        </el-table>
      </div>
      <div v-else-if="detailLoading" class="wms-empty">加载中…</div>
    </el-drawer>
  </section>
</template>
