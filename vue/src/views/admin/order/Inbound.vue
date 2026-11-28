<script setup>
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { createOrder, getOrder, updateOrder } from '../../../api/wms'
import { loadPartnerOptions, loadWarehouseOptions } from '../../../api/options'
import LocationSelect from '../../../components/LocationSelect.vue'
import ProductSelect from '../../../components/ProductSelect.vue'
import { STATUS_TAGS, useOrderPage } from '../../../composables/useOrderPage'
import { useCan } from '../../../utils/auth'
import { formatDateTime } from '../../../utils/format'

const can = useCan()
const {
  filters, rows, total, page: currentPage, size, loading, error, load, search, reset, changeSize,
  detail, detailVisible, detailLoading, detailError, actioning,
  openDetail, submit, approve, reject, remove,
} = useOrderPage('inbound', { source: null })

const warehouses = ref([])
const suppliers = ref([])

const dialogVisible = ref(false)
const editing = ref(null)
const submitting = ref(false)
const formError = ref('')
const form = reactive({ warehouseId: null, supplierId: null, remark: '', items: [] })

const totalAmount = computed(() => form.items.reduce((sum, item) => {
  const quantity = Number(item.quantity) || 0
  const price = Number(item.unitPrice) || 0
  return sum + quantity * price
}, 0))

onMounted(async () => {
  await Promise.allSettled([
    loadWarehouseOptions().then((data) => { warehouses.value = data }),
    loadPartnerOptions('supplier').then((data) => { suppliers.value = data }),
  ])
  await load()
})

function blankItem() {
  return {
    productId: null,
    locationId: null,
    batchNo: '',
    productionDate: null,
    expireDate: null,
    quantity: null,
    unitPrice: null,
    productLabel: '',
    locationLabel: '',
  }
}

function openCreate() {
  editing.value = null
  formError.value = ''
  Object.assign(form, {
    warehouseId: warehouses.value[0]?.id ?? null,
    supplierId: suppliers.value[0]?.id ?? null,
    remark: '',
    items: [blankItem()],
  })
  dialogVisible.value = true
}

async function openEdit(row) {
  formError.value = ''
  try {
    const data = await getOrder('inbound', row.id)
    editing.value = row
    Object.assign(form, {
      warehouseId: data.order.warehouseId,
      supplierId: data.order.supplierId,
      remark: data.order.remark || '',
      items: data.items.map((item) => ({
        productId: item.productId,
        locationId: item.locationId,
        batchNo: item.batchNo || '',
        productionDate: item.productionDate || null,
        expireDate: item.expireDate || null,
        quantity: Number(item.quantity),
        unitPrice: item.unitPrice == null ? null : Number(item.unitPrice),
        productLabel: `${item.skuCode} · ${item.productName}`,
        locationLabel: item.locationCode,
      })),
    })
    dialogVisible.value = true
  } catch (failure) {
    ElMessage.error(failure.message)
  }
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

function onLocationSelect(index, option) {
  form.items[index].locationLabel = option?.label || ''
}

function validate() {
  if (!form.warehouseId) return '请选择仓库'
  if (!form.supplierId) return '请选择供应商'
  if (!form.items.length) return '请至少录入一条明细'
  for (const [index, item] of form.items.entries()) {
    const row = `第 ${index + 1} 行明细：`
    if (!item.productId) return row + '请选择商品'
    if (!item.locationId) return row + '请选择库位'
    if (!item.quantity || Number(item.quantity) <= 0) return row + '数量必须大于 0'
    if (item.expireDate && item.productionDate && item.expireDate < item.productionDate) {
      return row + '失效日期不能早于生产日期'
    }
  }
  return ''
}

async function save() {
  formError.value = validate()
  if (formError.value) return
  submitting.value = true
  try {
    const body = {
      warehouseId: form.warehouseId,
      supplierId: form.supplierId,
      remark: form.remark || null,
      items: form.items.map((item) => ({
        productId: item.productId,
        locationId: item.locationId,
        batchNo: item.batchNo ? item.batchNo.trim() : null,
        productionDate: item.productionDate || null,
        expireDate: item.expireDate || null,
        quantity: item.quantity,
        unitPrice: item.unitPrice === '' ? null : item.unitPrice,
      })),
    }
    if (editing.value) await updateOrder('inbound', editing.value.id, body)
    else await createOrder('inbound', body)
    ElMessage.success(editing.value ? '入库单已更新' : '入库草稿已创建')
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
      <h1>入库单</h1>
      <p>草稿可修改、删除；提交后进入待审核，审核通过时一次性写入库存与流水，重复审核不会重复记账。</p>
    </div>

    <div class="surface wms-toolbar">
      <el-select v-model="filters.status" placeholder="状态" clearable>
        <el-option v-for="(item, key) in STATUS_TAGS" :key="key" :label="item.label" :value="Number(key)" />
      </el-select>
      <el-select v-model="filters.warehouseId" placeholder="仓库" clearable>
        <el-option v-for="item in warehouses" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-select v-model="filters.source" placeholder="来源" clearable>
        <el-option label="人工建单" value="MANUAL" />
        <el-option label="AI 助手" value="AI_AGENT" />
      </el-select>
      <el-input v-model="filters.keyword" placeholder="单号 / 备注" clearable @keyup.enter="search" />
      <el-date-picker v-model="filters.dateFrom" type="date" placeholder="起始日期" value-format="YYYY-MM-DD" style="width: 150px" />
      <el-date-picker v-model="filters.dateTo" type="date" placeholder="截止日期" value-format="YYYY-MM-DD" style="width: 150px" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <span class="wms-toolbar-spacer"></span>
      <el-button v-if="can('wms:inbound:add')" type="primary" @click="openCreate">新建入库单</el-button>
    </div>

    <p v-if="error" class="wms-inline-error" role="alert">{{ error }}</p>

    <div class="surface wms-card">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="orderNo" label="单号" width="150" />
        <el-table-column prop="warehouseName" label="仓库" width="120" />
        <el-table-column prop="supplierName" label="供应商" min-width="150" />
        <el-table-column label="来源" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.source === 'AI_AGENT'" type="primary" size="small" effect="plain">AI 助手</el-tag>
            <span v-else class="wms-muted">人工</span>
          </template>
        </el-table-column>
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
        <el-table-column label="金额合计" width="120" align="right">
          <template #default="{ row }"><span class="wms-num">{{ row.totalAmount }}</span></template>
        </el-table-column>
        <el-table-column label="创建时间" width="165">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="250" align="right" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="can('wms:inbound:edit') && [0, 3].includes(row.status)"
              link
              type="primary"
              @click="openEdit(row)"
            >编辑</el-button>
            <el-button
              v-if="can('wms:inbound:submit') && [0, 3].includes(row.status)"
              link
              type="primary"
              @click="submit(row)"
            >提交</el-button>
            <el-button
              v-if="can('wms:inbound:approve') && row.status === 1"
              link
              type="success"
              @click="approve(row)"
            >审核</el-button>
            <el-button
              v-if="can('wms:inbound:reject') && row.status === 1"
              link
              type="warning"
              @click="reject(row)"
            >驳回</el-button>
            <el-button
              v-if="can('wms:inbound:remove') && row.status === 0"
              link
              type="danger"
              @click="remove(row)"
            >删除</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="wms-empty">还没有入库单。</div></template>
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

    <el-dialog v-model="dialogVisible" :title="editing ? `编辑入库单 ${editing.orderNo}` : '新建入库单'" width="1140px" top="5vh">
      <p v-if="formError" class="wms-inline-error" role="alert">{{ formError }}</p>
      <el-form label-position="top">
        <div class="wms-form-grid">
          <el-form-item label="仓库" required>
            <el-select v-model="form.warehouseId">
              <el-option v-for="item in warehouses" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="供应商" required>
            <el-select v-model="form.supplierId" filterable>
              <el-option v-for="item in suppliers" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="form.remark" maxlength="500" />
          </el-form-item>
        </div>

        <div class="wms-section">
          <div class="wms-section-title">
            <span>入库明细</span>
            <el-button size="small" @click="addItem">添加明细</el-button>
          </div>
          <p class="wms-hint">
            批次管理商品必须填写批次号，不按批次管理的商品统一使用 NO_BATCH；未填失效日期时按商品保质期自动补齐。
          </p>
          <div class="wms-item-row wms-item-head">
            <span>商品</span><span>库位</span><span>批次</span><span>数量</span><span>单价</span><span>生产日期</span><span>失效日期</span><span></span>
          </div>
          <div v-for="(item, index) in form.items" :key="index" class="wms-item-row">
            <ProductSelect
              v-model="item.productId"
              :fallback-label="item.productLabel"
              @select="(option) => onProductSelect(index, option)"
            />
            <LocationSelect
              v-model="item.locationId"
              :warehouse-id="form.warehouseId"
              :fallback-label="item.locationLabel"
              @select="(option) => onLocationSelect(index, option)"
            />
            <el-input v-model="item.batchNo" placeholder="批次号" maxlength="64" />
            <el-input v-model="item.quantity" placeholder="数量" />
            <el-input v-model="item.unitPrice" placeholder="单价" />
            <el-date-picker v-model="item.productionDate" type="date" placeholder="生产日期" value-format="YYYY-MM-DD" />
            <el-date-picker v-model="item.expireDate" type="date" placeholder="失效日期" value-format="YYYY-MM-DD" />
            <div class="wms-item-actions">
              <el-button link type="danger" @click="removeItem(index)">移除</el-button>
            </div>
          </div>
        </div>

        <div class="wms-summary">
          <span>明细条数 <strong>{{ form.items.length }}</strong></span>
          <span>
            数量合计
            <strong>{{ form.items.reduce((sum, item) => sum + (Number(item.quantity) || 0), 0) }}</strong>
          </span>
          <span>金额合计 <strong>{{ totalAmount.toFixed(2) }}</strong></span>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="save">保存草稿</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" size="58%" :title="detail ? `入库单 ${detail.order.orderNo}` : '入库单详情'">
      <p v-if="detailError" class="wms-inline-error" role="alert">{{ detailError }}</p>
      <div v-if="detail" v-loading="detailLoading">
        <div class="wms-detail">
          <div><span>状态</span><strong>{{ detail.order.statusText }}</strong></div>
          <div><span>仓库</span><strong>{{ detail.order.warehouseName }}</strong></div>
          <div><span>供应商</span><strong>{{ detail.order.supplierName }}</strong></div>
          <div><span>来源</span><strong>{{ detail.order.source === 'AI_AGENT' ? 'AI 助手' : '人工建单' }}</strong></div>
          <div><span>创建人</span><strong>{{ detail.order.creatorName }}</strong></div>
          <div><span>创建时间</span><strong>{{ formatDateTime(detail.order.createdAt) }}</strong></div>
          <div><span>提交时间</span><strong>{{ formatDateTime(detail.order.submittedAt) }}</strong></div>
          <div><span>审核人</span><strong>{{ detail.order.reviewerName || '—' }}</strong></div>
          <div><span>审核时间</span><strong>{{ formatDateTime(detail.order.reviewedAt) }}</strong></div>
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
          <el-table-column label="生产/失效" width="190">
            <template #default="{ row }">{{ row.productionDate || '—' }} / {{ row.expireDate || '—' }}</template>
          </el-table-column>
          <el-table-column label="数量" width="100" align="right">
            <template #default="{ row }"><span class="wms-num">{{ row.quantity }}</span></template>
          </el-table-column>
          <el-table-column label="单价" width="100" align="right">
            <template #default="{ row }"><span class="wms-num">{{ row.unitPrice ?? '—' }}</span></template>
          </el-table-column>
          <el-table-column label="金额" width="110" align="right">
            <template #default="{ row }"><span class="wms-num">{{ row.amount ?? '—' }}</span></template>
          </el-table-column>
        </el-table>
      </div>
      <div v-else-if="detailLoading" class="wms-empty">加载中…</div>
    </el-drawer>
  </section>
</template>
