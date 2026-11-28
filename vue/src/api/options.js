import { getCategoryTree, listAreas, listLocations, listPartners, listProducts, listWarehouses } from './wms'

/**
 * 下拉选项的数据来源。基础资料体量小，统一取前 100 条；
 * 库位与商品在单据录入时支持按关键字远程搜索。
 */

export async function loadWarehouseOptions() {
  const data = await listWarehouses({ page: 1, size: 100, status: 1 })
  return data.records || []
}

export async function loadAreaOptions(warehouseId) {
  if (!warehouseId) return []
  const data = await listAreas({ warehouseId })
  return Array.isArray(data) ? data : []
}

export async function searchLocationOptions(warehouseId, keyword) {
  if (!warehouseId) return []
  const data = await listLocations({ page: 1, size: 50, warehouseId, keyword, status: 1 })
  return data.records || []
}

export async function searchProductOptions(keyword) {
  const data = await listProducts({ page: 1, size: 50, keyword })
  return data.records || []
}

export async function loadProductOptions() {
  const data = await listProducts({ page: 1, size: 100 })
  return data.records || []
}

export async function loadPartnerOptions(type) {
  const data = await listPartners(type, { page: 1, size: 100 })
  return data.records || []
}

export async function loadCategoryOptions() {
  const tree = await getCategoryTree()
  const flat = []
  const walk = (nodes, depth) => {
    for (const node of nodes || []) {
      flat.push({ id: node.id, name: node.name, depth })
      walk(node.children, depth + 1)
    }
  }
  walk(tree, 0)
  return flat
}
