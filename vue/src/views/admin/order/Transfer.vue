<script setup>
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { createOrder } from '../../../api/wms'
import { loadWarehouseOptions } from '../../../api/options'
import LocationSelect from '../../../components/LocationSelect.vue'
import ProductSelect from '../../../components/ProductSelect.vue'
import { STATUS_TAGS, useOrderPage } from '../../../composables/useOrderPage'
import { useCan } from '../../../utils/auth'
import { formatDateTime } from '../../../utils/format'

const can = useCan()
const {
  filters, rows, total, page: currentPage, size, loading, error, load, search, reset, changeSize,
  detail, detailVisible, detailLoading, detailError,
  openDetail, submit, approve, reject,
} = useOrderPage('transfer')

const warehouses = ref([])
const dialogVisible = ref(false)
const submitting = ref(false)
const formError = ref('')
const form = reactive({ fromWarehouseId: null, toWarehouseId: null, remark: '', items: [] })

onMounted(async () => {
  try {
    warehouses.value = await loadWarehouseOptions()
  } catch (failure) {
    error.value = failure.message
  }
  await load()
})

function blankItem() {
  return {
    productId: null,
    fromLocationId: null,
    toLocationId: null,
    batchNo: '',
    quantity: null,
    productLabel: '',
    fromLocationLabel: '',
    toLocationLabel: '',
  }
}

function openCreate() {
  formError.value = ''
  const first = warehouses.value[0]?.id ?? null
  Object.assign(form, {
    fromWarehouseId: first,
    toWarehouseId: first,
    remark: '',
    items: [blankItem()],
  })
  dialogVisible.value = true
}

function addItem() {
  if (form.items.length >= 50) {
    ElMessage.warning('单张单据最多 50 条明细')
    return
  }
  form.items.push(blankItem())
}

function removeItem(index) {
  if (form.items.length === 1) {
    ElMessage.warning('至少保留一条明细')
    return
  }
  form.items.splice(index, 1)
}

function onProductSelect(index, option) {
  const item = form.items[index]
  item.productLabel = option?.label || ''
  if (option?.batchManaged === 0) item.batchNo = 'NO_BATCH'
  if (option?.batchManaged === 1 && item.batchNo === 'NO_BATCH') item.batchNo = ''
}

function onWarehouseChange() {
  for (const item of form.items) {
    item.fromLocationId = null
    item.toLocationId = null
    item.fromLocationLabel = ''
    item.toLocationLabel = ''
  }
}

function validate() {
  if (!form.fromWarehouseId || !form.toWarehouseId) return '请选择源仓库与目标仓库'
  for (const [index, item] of form.items.entries()) {
    const row = `第 ${index + 1} 行明细：`
    if (!item.productId) return row + '请选择商品'
    if (!item.fromLocationId) return row + '请选择源库位'
    if (!item.toLocationId) return row + '请选择目标库位'
    if (item.fromLocationId === item.toLocationId) return row + '源库位与目标库位不能相同'
    if (!item.quantity || Number(item.quantity) <= 0) return row + '数量必须大于 0'
  }
  return ''
}

async function save() {
  formError.value = validate()
  if (formError.value) return
  submitting.value = true
  try {
    await createOrder('transfer', {
      fromWarehouseId: form.fromWarehouseId,
      toWarehouseId: form.toWarehouseId,
      remark: form.remark || null,
      items: form.items.map((item) => ({
        productId: item.productId,
        fromLocationId: item.fromLocationId,
        toLocationId: item.toLocationId,
        batchNo: item.batchNo ? item.batchNo.trim() : null,
        quantity: item.quantity,
      })),
    })
    ElMessage.success('移库单草稿已创建')
    dialogVisible.value = false
    await load()
  } catch (failure) {
    formError.value = failure.message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <h1>移库单</h1>
      <p>支持同仓库换库位与跨仓移库；审核时源库位扣减与目标库位增加在同一事务内完成。</p>
    </div>

    <div class="surface wms-toolbar">
      <el-select v-model="filters.status" placeholder="状态" clearable>
        <el-option v-for="(item, key) in STATUS_TAGS" :key="key" :label="item.label" :value="Number(key)" />
      </el-select>
      <el-select v-model="filters.warehouseId" placeholder="仓库（源或目标）" clearable>
        <el-option v-for="item in warehouses" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-input v-model="filters.keyword" placeholder="单号 / 备注" clearable @keyup.enter="search" />
      <el-date-picker v-model="filters.dateFrom" type="date" placeholder="起始日期" value-format="YYYY-MM-DD" style="width: 150px" />
      <el-date-picker v-model="filters.dateTo" type="date" placeholder="截止日期" value-format="YYYY-MM-DD" style="width: 150px" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <span class="wms-toolbar-spacer"></span>
      <el-button v-if="can('wms:transfer:add')" type="primary" @click="openCreate">新建移库单</el-button>
    </div>

    <p v-if="error" class="wms-inline-error" role="alert">{{ error }}</p>

    <div class="surface wms-card">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="orderNo" label="单号" width="150" />
        <el-table-column prop="fromWarehouseName" label="源仓库" width="130" />
        <el-table-column prop="toWarehouseName" label="目标仓库" width="130" />
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
        <el-table-column label="数量合计" width="110" align="right">
          <template #default="{ row }"><span class="wms-num">{{ row.totalQuantity }}</span></template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="160">
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="165">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210" align="right" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="can('wms:transfer:add') && row.status === 0" link type="primary" @click="submit(row)">提交</el-button>
            <el-button v-if="can('wms:transfer:approve') && row.status === 1" link type="success" @click="approve(row)">审核</el-button>
            <el-button v-if="can('wms:transfer:approve') && row.status === 1" link type="warning" @click="reject(row)">驳回</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="wms-empty">还没有移库单。</div></template>
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

    <el-dialog v-model="dialogVisible" title="新建移库单" width="1080px" top="6vh">
      <p v-if="formError" class="wms-inline-error" role="alert">{{ formError }}</p>
      <el-form label-position="top">
        <div class="wms-form-grid">
          <el-form-item label="源仓库" required>
            <el-select v-model="form.fromWarehouseId" @change="onWarehouseChange">
              <el-option v-for="item in warehouses" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="目标仓库" required>
            <el-select v-model="form.toWarehouseId" @change="onWarehouseChange">
              <el-option v-for="item in warehouses" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="form.remark" maxlength="500" />
          </el-form-item>
        </div>

        <div class="wms-section">
          <div class="wms-section-title">
            <span>移库明细</span>
            <el-button size="small" @click="addItem">添加明细</el-button>
          </div>
          <p class="wms-hint">同仓库换库位时两边仓库相同即可，只需保证源库位与目标库位不同。</p>
          <div class="wms-item-row wms-item-head" style="grid-template-columns: 1.5fr 1.2fr 1.2fr 1.1fr 0.8fr auto">
            <span>商品</span><span>源库位</span><span>目标库位</span><span>批次</span><span>数量</span><span></span>
          </div>
          <div
            v-for="(item, index) in form.items"
            :key="index"
            class="wms-item-row"
            style="grid-template-columns: 1.5fr 1.2fr 1.2fr 1.1fr 0.8fr auto"
          >
            <ProductSelect v-model="item.productId" :fallback-label="item.productLabel" @select="(option) => onProductSelect(index, option)" />
            <LocationSelect
              v-model="item.fromLocationId"
              :warehouse-id="form.fromWarehouseId"
              :fallback-label="item.fromLocationLabel"
              placeholder="源库位"
            />
            <LocationSelect
              v-model="item.toLocationId"
              :warehouse-id="form.toWarehouseId"
              :fallback-label="item.toLocationLabel"
              placeholder="目标库位"
            />
            <el-input v-model="item.batchNo" placeholder="批次号" maxlength="64" />
            <el-input v-model="item.quantity" placeholder="数量" />
            <div class="wms-item-actions">
              <el-button link type="danger" @click="removeItem(index)">移除</el-button>
            </div>
          </div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="save">保存草稿</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" size="58%" :title="detail ? `移库单 ${detail.order.orderNo}` : '移库单详情'">
      <p v-if="detailError" class="wms-inline-error" role="alert">{{ detailError }}</p>
      <div v-if="detail" v-loading="detailLoading">
        <div class="wms-detail">
          <div><span>状态</span><strong>{{ detail.order.statusText }}</strong></div>
          <div><span>源仓库</span><strong>{{ detail.order.fromWarehouseName }}</strong></div>
          <div><span>目标仓库</span><strong>{{ detail.order.toWarehouseName }}</strong></div>
          <div><span>创建人</span><strong>{{ detail.order.creatorName }}</strong></div>
          <div><span>创建时间</span><strong>{{ formatDateTime(detail.order.createdAt) }}</strong></div>
          <div><span>审核人</span><strong>{{ detail.order.reviewerName || '—' }}</strong></div>
          <div><span>记账时间</span><strong>{{ formatDateTime(detail.order.postedAt) }}</strong></div>
          <div v-if="detail.order.rejectReason" class="wms-detail-wide">
            <span>驳回原因</span><strong class="wms-danger">{{ detail.order.rejectReason }}</strong>
          </div>
        </div>
        <el-table :data="detail.items" size="small" row-key="id">
          <el-table-column prop="skuCode" label="SKU" width="130" />
          <el-table-column prop="productName" label="商品" min-width="150" />
          <el-table-column prop="fromLocationCode" label="源库位" width="110" />
          <el-table-column prop="toLocationCode" label="目标库位" width="120" />
          <el-table-column prop="batchNo" label="批次" width="120" />
          <el-table-column label="数量" width="100" align="right">
            <template #default="{ row }"><span class="wms-num">{{ row.quantity }}</span></template>
          </el-table-column>
        </el-table>
      </div>
      <div v-else-if="detailLoading" class="wms-empty">加载中…</div>
    </el-drawer>
  </section>
</template>
