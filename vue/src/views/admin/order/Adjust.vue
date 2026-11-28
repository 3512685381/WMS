<script setup>
import { onMounted, ref } from 'vue'
import { loadWarehouseOptions } from '../../../api/options'
import { STATUS_TAGS, useOrderPage } from '../../../composables/useOrderPage'
import { useCan } from '../../../utils/auth'
import { formatDateTime } from '../../../utils/format'

const can = useCan()
const {
  filters, rows, total, page: currentPage, size, loading, error, load, search, reset, changeSize,
  detail, detailVisible, detailLoading, detailError,
  openDetail, submit, approve, reject,
} = useOrderPage('adjust')

const warehouses = ref([])

onMounted(async () => {
  try {
    warehouses.value = await loadWarehouseOptions()
  } catch (failure) {
    error.value = failure.message
  }
  await load()
})
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <h1>库存调整单</h1>
      <p>调整单由盘点差异自动生成，不能手工新建；审核前会再次核对库存版本，版本变化时要求重新核对。</p>
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
    </div>

    <p v-if="error" class="wms-inline-error" role="alert">{{ error }}</p>

    <div class="surface wms-card">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="orderNo" label="单号" width="150" />
        <el-table-column prop="warehouseName" label="仓库" width="130" />
        <el-table-column prop="stocktakeOrderNo" label="来源盘点单" width="150">
          <template #default="{ row }">{{ row.stocktakeOrderNo || '手工' }}</template>
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
        <el-table-column label="调整数量合计" width="130" align="right">
          <template #default="{ row }"><span class="wms-num">{{ row.totalQuantity }}</span></template>
        </el-table-column>
        <el-table-column label="创建时间" width="165">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210" align="right" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="can('wms:adjust:add') && [0, 3].includes(row.status)" link type="primary" @click="submit(row)">提交</el-button>
            <el-button v-if="can('wms:adjust:approve') && row.status === 1" link type="success" @click="approve(row)">审核</el-button>
            <el-button v-if="can('wms:adjust:approve') && row.status === 1" link type="warning" @click="reject(row)">驳回</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="wms-empty">还没有调整单，盘点有差异时会自动生成。</div></template>
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

    <el-drawer v-model="detailVisible" size="58%" :title="detail ? `调整单 ${detail.order.orderNo}` : '调整单详情'">
      <p v-if="detailError" class="wms-inline-error" role="alert">{{ detailError }}</p>
      <div v-if="detail" v-loading="detailLoading">
        <div class="wms-detail">
          <div><span>状态</span><strong>{{ detail.order.statusText }}</strong></div>
          <div><span>仓库</span><strong>{{ detail.order.warehouseName }}</strong></div>
          <div><span>来源盘点单</span><strong>{{ detail.order.stocktakeOrderNo || '手工' }}</strong></div>
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
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              <el-tag :type="row.adjustType === 'GAIN' ? 'success' : 'danger'" size="small">
                {{ row.adjustType === 'GAIN' ? '盘盈' : '盘亏' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="调整数量" width="110" align="right">
            <template #default="{ row }"><span class="wms-num">{{ row.adjustQty }}</span></template>
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
