<script setup>
import PartnerTable from './PartnerTable.vue'
</script>

<template>
  <PartnerTable
    type="supplier"
    title="供应商"
    description="入库单必须选择供应商；已被入库单引用的供应商不能删除，也不能修改编码。"
    permission-key="supplier"
  />
</template>
