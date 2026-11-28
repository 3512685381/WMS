<script setup>
import { onMounted, ref } from 'vue'
import { listExpiring, listLowStock, listOverstock } from '../../../api/wms'
import { loadWarehouseOptions } from '../../../api/options'
import { usePagedList } from '../../../composables/usePagedList'

const warehouses = ref([])
const tab = ref('low')
const warehouseId = ref(null)

const {
  filters: lowFilters, rows: lowRows, total: lowTotal, page: lowPage, size: lowSize,
  loading: lowLoading, error: lowError, load: loadLow, search: searchLow,
} = usePagedList(listLowStock, { warehouseId: null, keyword: '', includeMissing: true })

const {
  filters: overFilters, rows: overRows, total: overTotal, page: overPage, size: overSize,
  loading: overLoading, error: overError, load: loadOver, search: searchOver,
} = usePagedList(listOverstock, { warehouseId: null, keyword: '' })

const {
  filters: expFilters, rows: expRows, total: expTotal, page: expPage, size: expSize,
  loading: expLoading, error: expError, load: loadExp, search: searchExp,
} = usePagedList(listExpiring, { warehouseId: null, days: 30, scope: 'EXPIRING' })

const keyword = ref('')

onMounted(async () => {
  try {
    warehouses.value = await loadWarehouseOptions()
  } catch {
    warehouses.value = []
  }
  await Promise.all([loadLow(), loadOver(), loadExp()])
})

async function refresh() {
  lowFilters.warehouseId = warehouseId.value
  overFilters.warehouseId = warehouseId.value
  expFilters.warehouseId = warehouseId.value
  lowFilters.keyword = keyword.value
  overFilters.keyword = keyword.value
  if (tab.value === 'low') await searchLow()
  else if (tab.value === 'over') await searchOver()
  else await searchExp()
}
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <h1>库存预警</h1>
      <p>低库存对比商品安全库存、超储对比库存上限、临期按失效日期筛选；统计口径与 AI 助手一致。</p>
    </div>

    <div class="surface wms-toolbar">
      <el-select v-model="warehouseId" placeholder="仓库" clearable @change="refresh">
        <el-option v-for="item in warehouses" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-input v-if="tab !== 'expiring'" v-model="keyword" placeholder="SKU / 商品名称" clearable @keyup.enter="refresh" />
      <template v-else>
        <el-input v-model="expFilters.days" placeholder="剩余天数" style="width: 120px" />
        <el-select v-model="expFilters.scope" style="width: 140px">
          <el-option label="即将到期" value="EXPIRING" />
          <el-option label="已经过期" value="EXPIRED" />
          <el-option label="全部批次" value="ALL" />
        </el-select>
      </template>
      <el-button type="primary" @click="refresh">查询</el-button>
    </div>

    <div class="surface wms-card">
      <div class="wms-card-head">
        <el-tabs v-model="tab">
          <el-tab-pane label="低库存" name="low" />
          <el-tab-pane label="超储" name="over" />
          <el-tab-pane label="临期" name="expiring" />
        </el-tabs>
      </div>

      <template v-if="tab === 'low'">
        <p v-if="lowError" class="wms-inline-error" role="alert">{{ lowError }}</p>
        <el-table v-loading="lowLoading" :data="lowRows" row-key="productId">
          <el-table-column prop="warehouseName" label="仓库" width="130" />
          <el-table-column prop="skuCode" label="SKU" width="140" />
          <el-table-column prop="productName" label="商品" min-width="170" />
          <el-table-column label="现有" width="130" align="right">
            <template #default="{ row }">
              <span class="wms-num">{{ row.quantity }}</span>
              <span v-if="row.noStockRecord" class="wms-muted"> 未建库存</span>
            </template>
          </el-table-column>
          <el-table-column label="安全库存" width="110" align="right">
            <template #default="{ row }"><span class="wms-num">{{ row.minStock }}</span></template>
          </el-table-column>
          <el-table-column label="缺口" width="110" align="right">
            <template #default="{ row }"><strong class="wms-danger wms-num">{{ row.shortage }}</strong></template>
          </el-table-column>
          <template #empty><div class="wms-empty">没有低库存商品。</div></template>
        </el-table>
        <div class="wms-pager">
          <el-pagination
            v-model:current-page="lowPage"
            :total="lowTotal"
            :page-size="lowSize"
            layout="total, prev, pager, next"
            @current-change="loadLow"
          />
        </div>
      </template>

      <template v-else-if="tab === 'over'">
        <p v-if="overError" class="wms-inline-error" role="alert">{{ overError }}</p>
        <el-table v-loading="overLoading" :data="overRows" row-key="productId">
          <el-table-column prop="warehouseName" label="仓库" width="130" />
          <el-table-column prop="skuCode" label="SKU" width="140" />
          <el-table-column prop="productName" label="商品" min-width="170" />
          <el-table-column label="现有" width="110" align="right">
            <template #default="{ row }"><span class="wms-num">{{ row.quantity }}</span></template>
          </el-table-column>
          <el-table-column label="库存上限" width="110" align="right">
            <template #default="{ row }"><span class="wms-num">{{ row.maxStock }}</span></template>
          </el-table-column>
          <el-table-column label="超出" width="110" align="right">
            <template #default="{ row }"><strong class="wms-danger wms-num">{{ row.excess }}</strong></template>
          </el-table-column>
          <template #empty><div class="wms-empty">没有超储商品。</div></template>
        </el-table>
        <div class="wms-pager">
          <el-pagination
            v-model:current-page="overPage"
            :total="overTotal"
            :page-size="overSize"
            layout="total, prev, pager, next"
            @current-change="loadOver"
          />
        </div>
      </template>

      <template v-else>
        <p v-if="expError" class="wms-inline-error" role="alert">{{ expError }}</p>
        <el-table v-loading="expLoading" :data="expRows" row-key="inventoryId">
          <el-table-column prop="warehouseName" label="仓库" width="120" />
          <el-table-column prop="locationCode" label="库位" width="110" />
          <el-table-column prop="skuCode" label="SKU" width="130" />
          <el-table-column prop="productName" label="商品" min-width="160" />
          <el-table-column prop="batchNo" label="批次" width="130" />
          <el-table-column label="失效日期" width="120">
            <template #default="{ row }"><span class="wms-num">{{ row.expireDate }}</span></template>
          </el-table-column>
          <el-table-column label="剩余天数" width="130" align="right">
            <template #default="{ row }">
              <el-tag
                :type="Number(row.daysLeft) < 0 ? 'danger' : Number(row.daysLeft) <= 7 ? 'warning' : 'info'"
                size="small"
              >
                {{ Number(row.daysLeft) < 0 ? `已过期 ${Math.abs(row.daysLeft)} 天` : `${row.daysLeft} 天` }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="可用" width="100" align="right">
            <template #default="{ row }"><span class="wms-num">{{ row.availableQuantity }}</span></template>
          </el-table-column>
          <template #empty><div class="wms-empty">没有临期批次。</div></template>
        </el-table>
        <div class="wms-pager">
          <el-pagination
            v-model:current-page="expPage"
            :total="expTotal"
            :page-size="expSize"
            layout="total, prev, pager, next"
            @current-change="loadExp"
          />
        </div>
      </template>
    </div>
  </section>
</template>
