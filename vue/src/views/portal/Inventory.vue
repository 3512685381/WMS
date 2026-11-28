<script setup>
import { onMounted, ref } from 'vue'
import { listInventory } from '../../api/wms'
import { loadWarehouseOptions } from '../../api/options'
import { usePagedList } from '../../composables/usePagedList'
import { useCan } from '../../utils/auth'

const can = useCan()
const canPickWarehouse = can('wms:warehouse:list')
const warehouses = ref([])
const { filters, rows, total, page, size, loading, error, load, search, reset, changeSize } = usePagedList(
  listInventory,
  { warehouseId: null, keyword: '', batchNo: '', onlyAvailable: false },
)

onMounted(async () => {
  if (canPickWarehouse) {
    warehouses.value = await loadWarehouseOptions().catch(() => [])
  }
  await load()
})
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <h1>库存查询</h1>
      <p>实时查看各仓库、库位与批次的可用数量；可用量 = 库存数量 − 冻结数量。</p>
    </div>

    <div class="surface wms-toolbar">
      <el-select v-if="canPickWarehouse" v-model="filters.warehouseId" placeholder="仓库" clearable>
        <el-option v-for="item in warehouses" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-input v-model="filters.keyword" placeholder="商品名称或 SKU" clearable @keyup.enter="search" />
      <el-input v-model="filters.batchNo" placeholder="批次号" clearable @keyup.enter="search" />
      <el-checkbox v-model="filters.onlyAvailable" label="只看有可用量" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </div>

    <p v-if="error" class="wms-inline-error" role="alert">{{ error }}</p>

    <div class="surface wms-card">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="warehouseName" label="仓库" width="130" />
        <el-table-column prop="locationCode" label="库位" width="120" />
        <el-table-column prop="skuCode" label="SKU" width="140" />
        <el-table-column prop="productName" label="商品" min-width="180" />
        <el-table-column prop="batchNo" label="批次" width="140" />
        <el-table-column label="数量" width="110" align="right">
          <template #default="{ row }"><span class="wms-num">{{ row.quantity }}</span></template>
        </el-table-column>
        <el-table-column label="冻结" width="100" align="right">
          <template #default="{ row }"><span class="wms-num">{{ row.frozenQuantity }}</span></template>
        </el-table-column>
        <el-table-column label="可用" width="110" align="right">
          <template #default="{ row }">
            <strong class="wms-num">{{ row.availableQuantity }}</strong>
            <span class="wms-muted"> {{ row.unit }}</span>
          </template>
        </el-table-column>
        <template #empty><div class="wms-empty">没有符合条件的库存。</div></template>
      </el-table>
      <div class="wms-pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="changeSize"
        />
      </div>
    </div>
  </section>
</template>
