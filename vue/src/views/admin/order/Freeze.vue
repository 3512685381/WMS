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
} = useOrderPage('freeze')

const warehouses = ref([])
const dialogVisible = ref(false)
const submitting = ref(false)
const formError = ref('')
const form = reactive({ warehouseId: null, remark: '', items: [] })

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
    locationId: null,
    batchNo: '',
    opType: 'FREEZE',
    quantity: null,
    reason: '',
    productLabel: '',
    locationLabel: '',
  }
}

function openCreate() {
  formError.value = ''
  Object.assign(form, {
    warehouseId: warehouses.value[0]?.id ?? null,
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

function validate() {
  if (!form.warehouseId) return '请选择仓库'
  for (const [index, item] of form.items.entries()) {
    const row = `第 ${index + 1} 行明细：`
    if (!item.productId) return row + '请选择商品'
    if (!item.locationId) return row + '请选择库位'
    if (!item.quantity || Number(item.quantity) <= 0) return row + '数量必须大于 0'
  }
  return ''
}

async function save() {
  formError.value = validate()
  if (formError.value) return
  submitting.value = true
  try {
    await createOrder('freeze', {
      warehouseId: form.warehouseId,
      remark: form.remark || null,
      items: form.items.map((item) => ({
        productId: item.productId,
        locationId: item.locationId,
        batchNo: item.batchNo ? item.batchNo.trim() : null,
        opType: item.opType,
        quantity: item.quantity,
        reason: item.reason || null,
      })),
    })
    ElMessage.success('冻结单草稿已创建')
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
      <h1>冻结与解冻单</h1>
      <p>冻结只改冻结数量、不动库存数量；解冻量不能超过已冻结量，冻结量不能超过库存数量。</p>
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
      <el-button v-if="can('wms:freeze:add')" type="primary" @click="openCreate">新建冻结单</el-button>
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
        <el-table-column label="数量合计" width="110" align="right">
          <template #default="{ row }"><span class="wms-num">{{ row.totalQuantity }}</span></template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="180">
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="165">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210" align="right" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="can('wms:freeze:add') && row.status === 0" link type="primary" @click="submit(row)">提交</el-button>
            <el-button v-if="can('wms:freeze:approve') && row.status === 1" link type="success" @click="approve(row)">审核</el-button>
            <el-button v-if="can('wms:freeze:approve') && row.status === 1" link type="warning" @click="reject(row)">驳回</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="wms-empty">还没有冻结单。</div></template>
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

    <el-dialog v-model="dialogVisible" title="新建冻结单" width="1000px" top="6vh">
      <p v-if="formError" class="wms-inline-error" role="alert">{{ formError }}</p>
      <el-form label-position="top">
        <div class="wms-form-grid">
          <el-form-item label="仓库" required>
            <el-select v-model="form.warehouseId">
              <el-option v-for="item in warehouses" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="form.remark" maxlength="500" />
          </el-form-item>
        </div>

        <div class="wms-section">
          <div class="wms-section-title">
            <span>冻结明细</span>
            <el-button size="small" @click="addItem">添加明细</el-button>
          </div>
          <div class="wms-item-row wms-item-head" style="grid-template-columns: 1.6fr 1.3fr 1.1fr 0.9fr 0.8fr 1.2fr auto">
            <span>商品</span><span>库位</span><span>批次</span><span>操作</span><span>数量</span><span>原因</span><span></span>
          </div>
          <div
            v-for="(item, index) in form.items"
            :key="index"
            class="wms-item-row"
            style="grid-template-columns: 1.6fr 1.3fr 1.1fr 0.9fr 0.8fr 1.2fr auto"
          >
            <ProductSelect v-model="item.productId" :fallback-label="item.productLabel" @select="(option) => onProductSelect(index, option)" />
            <LocationSelect v-model="item.locationId" :warehouse-id="form.warehouseId" :fallback-label="item.locationLabel" />
            <el-input v-model="item.batchNo" placeholder="批次号" maxlength="64" />
            <el-select v-model="item.opType">
              <el-option label="冻结" value="FREEZE" />
              <el-option label="解冻" value="UNFREEZE" />
            </el-select>
            <el-input v-model="item.quantity" placeholder="数量" />
            <el-input v-model="item.reason" placeholder="原因" maxlength="500" />
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

    <el-drawer v-model="detailVisible" size="58%" :title="detail ? `冻结单 ${detail.order.orderNo}` : '冻结单详情'">
      <p v-if="detailError" class="wms-inline-error" role="alert">{{ detailError }}</p>
      <div v-if="detail" v-loading="detailLoading">
        <div class="wms-detail">
          <div><span>状态</span><strong>{{ detail.order.statusText }}</strong></div>
          <div><span>仓库</span><strong>{{ detail.order.warehouseName }}</strong></div>
          <div><span>创建人</span><strong>{{ detail.order.creatorName }}</strong></div>
          <div><span>创建时间</span><strong>{{ formatDateTime(detail.order.createdAt) }}</strong></div>
          <div><span>审核人</span><strong>{{ detail.order.reviewerName || '—' }}</strong></div>
          <div><span>记账时间</span><strong>{{ formatDateTime(detail.order.postedAt) }}</strong></div>
          <div v-if="detail.order.rejectReason" class="wms-detail-wide">
            <span>驳回原因</span><strong class="wms-danger">{{ detail.order.rejectReason }}</strong>
          </div>
          <div v-if="detail.order.remark" class="wms-detail-wide">
            <span>备注</span><strong>{{ detail.order.remark }}</strong>
          </div>
        </div>
        <el-table :data="detail.items" size="small" row-key="id">
          <el-table-column prop="skuCode" label="SKU" width="130" />
          <el-table-column prop="productName" label="商品" min-width="150" />
          <el-table-column prop="locationCode" label="库位" width="110" />
          <el-table-column prop="batchNo" label="批次" width="120" />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-tag :type="row.opType === 'FREEZE' ? 'warning' : 'success'" size="small">
                {{ row.opType === 'FREEZE' ? '冻结' : '解冻' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="数量" width="100" align="right">
            <template #default="{ row }"><span class="wms-num">{{ row.quantity }}</span></template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" min-width="140">
            <template #default="{ row }">{{ row.reason || '—' }}</template>
          </el-table-column>
        </el-table>
      </div>
      <div v-else-if="detailLoading" class="wms-empty">加载中…</div>
    </el-drawer>
  </section>
</template>
