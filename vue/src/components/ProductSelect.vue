<script setup>
import { computed, onMounted, ref } from 'vue'
import { searchProductOptions } from '../api/options'

const props = defineProps({
  modelValue: { type: [Number, String], default: null },
  fallbackLabel: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  placeholder: { type: String, default: '搜索 SKU 或商品名称' },
})
const emit = defineEmits(['update:modelValue', 'select'])

const options = ref([])
const loading = ref(false)
const cache = new Map()

const selected = computed(() => {
  if (props.modelValue === null || props.modelValue === undefined) return null
  const hit = cache.get(props.modelValue)
  if (hit) return hit
  if (!props.fallbackLabel) return null
  const synthetic = { id: props.modelValue, label: props.fallbackLabel }
  cache.set(props.modelValue, synthetic)
  return synthetic
})

const visibleOptions = computed(() => {
  const list = options.value.slice()
  if (selected.value && !list.some((item) => item.id === selected.value.id)) list.unshift(selected.value)
  return list
})

onMounted(() => load(''))

async function load(keyword) {
  loading.value = true
  try {
    const rows = await searchProductOptions(keyword)
    for (const row of rows) {
      cache.set(row.id, {
        id: row.id,
        label: `${row.skuCode} · ${row.name}`,
        unit: row.unit,
        batchManaged: row.batchManaged,
        row,
      })
    }
    options.value = rows.map((row) => cache.get(row.id))
  } catch {
    options.value = []
  } finally {
    loading.value = false
  }
}

function change(value) {
  emit('update:modelValue', value)
  emit('select', cache.get(value) || null)
}
</script>

<template>
  <el-select
    :model-value="modelValue"
    :disabled="disabled"
    :placeholder="placeholder"
    filterable
    remote
    clearable
    :remote-method="load"
    :loading="loading"
    @change="change"
  >
    <el-option v-for="item in visibleOptions" :key="item.id" :label="item.label" :value="item.id" />
  </el-select>
</template>
