<script setup>
import { onMounted, ref, watch } from 'vue'
import { BIZ_TYPES, listInventory, listInventoryLogs } from '../../../api/wms'
import { loadWarehouseOptions } from '../../../api/options'
import ProductSelect from '../../../components/ProductSelect.vue'
import { usePagedList } from '../../../composables/usePagedList'
import { formatDateTime } from '../../../utils/format'

const warehouses = ref([])
const { filters, rows, total, page, size, loading, error, load, search, reset, changeSize } = usePagedList(
  listInventory,
  { warehouseId: null, productId: null, batchNo: '', keyword: '', onlyAvailable: false },
)

const logDrawer = ref(false)
const logFilters = ref({ bizType: '', orderNo: '', dateFrom: null, dateTo: null })
const logs = ref([])
const logTotal = ref(0)
const logPage = ref(1)
const logLoading = ref(false)
const logError = ref('')
const logTarget = ref(null)

onMounted(async () => {
  try {
    warehouses.value = await loadWarehouseOptions()
  } catch (failure) {
    error.value = failure.message
  }
  await load()
})

async function loadLogs(target) {
  const next = target || logPage.value
  logLoading.value = true
  logError.value = ''
  try {
    const data = await listInventoryLogs({
      ...logFilters.value,
      productId: logTarget.value?.productId,
      warehouseId: logTarget.value?.warehouseId,
      page: next,
      size: 20,
    })
    logs.value = data.records || []
    logTotal.value = Number(data.total || 0)
    logPage.value = Number(data.page || next)
  } catch (failure) {
    logError.value = failure.message
    logs.value = []
  } finally {
    logLoading.value = false
  }
}

function openLogs(row) {
  logTarget.value = row
  logFilters.value = { bizType: '', orderNo: '', dateFrom: null, dateTo: null }
  logPage.value = 1
  logDrawer.value = true
  loadLogs(1)
}

watch(logDrawer, (open) => {
  if (!open) logTarget.value = null
})
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <h1>实时库存</h1>
      <p>库存按「仓库 + 库位 + 商品 + 批次」记录；可用数量由数据库按「数量 − 冻结数量」自动计算。</p>
    </div>

    <div class="surface wms-toolbar">
      <el-select v-model="filters.warehouseId" placeholder="仓库" clearable>
        <el-option v-for="item in warehouses" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <ProductSelect v-model="filters.productId" placeholder="商品（可搜索）" />
      <el-input v-model="filters.batchNo" placeholder="批次号" clearable @keyup.enter="search" />
      <el-input v-model="filters.keyword" placeholder="库位 / SKU / 商品" clearable @keyup.enter="search" />
      <el-checkbox v-model="filters.onlyAvailable" label="只看有可用量" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </div>

    <p v-if="error" class="wms-inline-error" role="alert">{{ error }}</p>

    <div class="surface wms-card">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="warehouseName" label="仓库" width="120" />
        <el-table-column prop="locationCode" label="库位" width="120" />
        <el-table-column prop="skuCode" label="SKU" width="130" />
        <el-table-column prop="productName" label="商品" min-width="160" />
        <el-table-column prop="batchNo" label="批次" width="130" />
        <el-table-column label="效期" width="120">
          <template #default="{ row }">
            <span class="wms-num">{{ row.expireDate || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="100" align="right">
          <template #default="{ row }"><span class="wms-num">{{ row.quantity }}</span></template>
        </el-table-column>
        <el-table-column label="冻结" width="96" align="right">
          <template #default="{ row }"><span class="wms-num">{{ row.frozenQuantity }}</span></template>
        </el-table-column>
        <el-table-column label="可用" width="100" align="right">
          <template #default="{ row }">
            <strong class="wms-num">{{ row.availableQuantity }}</strong>
            <span class="wms-muted"> {{ row.unit }}</span>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="80" align="right">
          <template #default="{ row }"><span class="wms-num">{{ row.version }}</span></template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openLogs(row)">流水</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="wms-empty">没有符合条件的库存。</div></template>
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

    <el-drawer v-model="logDrawer" size="62%" :title="logTarget ? `库存流水 · ${logTarget.skuCode} / ${logTarget.batchNo}` : '库存流水'">
      <div class="wms-toolbar" style="padding-top: 0">
        <el-select v-model="logFilters.bizType" placeholder="业务类型" clearable>
          <el-option v-for="(label, key) in BIZ_TYPES" :key="key" :label="label" :value="key" />
        </el-select>
        <el-input v-model="logFilters.orderNo" placeholder="单号" clearable @keyup.enter="loadLogs(1)" />
        <el-date-picker
          v-model="logFilters.dateFrom"
          type="date"
          placeholder="起始日期"
          value-format="YYYY-MM-DD"
          style="width: 150px"
        />
        <el-date-picker
          v-model="logFilters.dateTo"
          type="date"
          placeholder="截止日期"
          value-format="YYYY-MM-DD"
          style="width: 150px"
        />
        <el-button type="primary" @click="loadLogs(1)">查询</el-button>
      </div>

      <p v-if="logError" class="wms-inline-error" role="alert">{{ logError }}</p>

      <el-table v-loading="logLoading" :data="logs" row-key="id" size="small">
        <el-table-column label="时间" width="165">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="业务" width="88">
          <template #default="{ row }">{{ BIZ_TYPES[row.bizType] || row.bizType }}</template>
        </el-table-column>
        <el-table-column prop="orderNo" label="单号" width="140" />
        <el-table-column label="数量变化" width="150" align="right">
          <template #default="{ row }">
            <span class="wms-num">{{ row.beforeQty }} → {{ row.afterQty }}</span>
            <span class="wms-muted">（{{ Number(row.changeQty) > 0 ? '+' : '' }}{{ row.changeQty }}）</span>
          </template>
        </el-table-column>
        <el-table-column label="冻结变化" width="150" align="right">
          <template #default="{ row }">
            <span class="wms-num">{{ row.beforeFrozenQty }} → {{ row.afterFrozenQty }}</span>
            <span class="wms-muted">（{{ Number(row.changeFrozenQty) > 0 ? '+' : '' }}{{ row.changeFrozenQty }}）</span>
          </template>
        </el-table-column>
        <el-table-column prop="operatorName" label="操作人" width="110" />
        <template #empty><div class="wms-empty">暂无流水记录。</div></template>
      </el-table>

      <div class="wms-pager">
        <el-pagination
          v-model:current-page="logPage"
          :total="logTotal"
          :page-size="20"
          layout="total, prev, pager, next"
          @current-change="loadLogs"
        />
      </div>
    </el-drawer>
  </section>
</template>
