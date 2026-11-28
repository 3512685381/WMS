<script setup>
import PartnerTable from './PartnerTable.vue'
</script>

<template>
  <PartnerTable
    type="customer"
    title="客户"
    description="出库单可以选择客户；已被出库单引用的客户不能删除，也不能修改编码。"
    permission-key="customer"
  />
</template>
