import http from './request'

const base = '/api/wms'

/** 去掉空值参数，避免后端把空字符串当成筛选条件。 */
function clean(params) {
  const output = {}
  for (const [key, value] of Object.entries(params || {})) {
    if (value === null || value === undefined || value === '') continue
    output[key] = value
  }
  return output
}

/* ------------------------------------------------------------------ 看板与预警 */

export const getDashboardSummary = () => http.get(`${base}/dashboard/summary`)

export const listLowStock = (params) => http.get(`${base}/alerts/low-stock`, { params: clean(params) })
export const listOverstock = (params) => http.get(`${base}/alerts/overstock`, { params: clean(params) })
export const listExpiring = (params) => http.get(`${base}/alerts/expiring`, { params: clean(params) })

/* ------------------------------------------------------------------ 库存 */

export const listInventory = (params) => http.get(`${base}/inventory`, { params: clean(params) })
export const getInventory = (id) => http.get(`${base}/inventory/${id}`)
export const listInventoryLogs = (params) => http.get(`${base}/inventory/logs`, { params: clean(params) })

/* ------------------------------------------------------------------ 基础资料 */

export const listWarehouses = (params) => http.get(`${base}/warehouses`, { params: clean(params) })
export const createWarehouse = (body) => http.post(`${base}/warehouses`, body)
export const updateWarehouse = (id, body) => http.put(`${base}/warehouses/${id}`, body)
export const deleteWarehouse = (id) => http.delete(`${base}/warehouses/${id}`)

export const listAreas = (params) => http.get(`${base}/areas`, { params: clean(params) })
export const createArea = (body) => http.post(`${base}/areas`, body)
export const updateArea = (id, body) => http.put(`${base}/areas/${id}`, body)
export const deleteArea = (id) => http.delete(`${base}/areas/${id}`)

export const listLocations = (params) => http.get(`${base}/locations`, { params: clean(params) })
export const createLocation = (body) => http.post(`${base}/locations`, body)
export const updateLocation = (id, body) => http.put(`${base}/locations/${id}`, body)
export const deleteLocation = (id) => http.delete(`${base}/locations/${id}`)

export const getCategoryTree = () => http.get(`${base}/categories`)
export const createCategory = (body) => http.post(`${base}/categories`, body)
export const updateCategory = (id, body) => http.put(`${base}/categories/${id}`, body)
export const deleteCategory = (id) => http.delete(`${base}/categories/${id}`)

export const listProducts = (params) => http.get(`${base}/products`, { params: clean(params) })
export const getProduct = (id) => http.get(`${base}/products/${id}`)
export const createProduct = (body) => http.post(`${base}/products`, body)
export const updateProduct = (id, body) => http.put(`${base}/products/${id}`, body)
export const deleteProduct = (id) => http.delete(`${base}/products/${id}`)

const partnerPath = (type) => (type === 'customer' ? 'customers' : 'suppliers')

export const listPartners = (type, params) => http.get(`${base}/${partnerPath(type)}`, { params: clean(params) })
export const createPartner = (type, body) => http.post(`${base}/${partnerPath(type)}`, body)
export const updatePartner = (type, id, body) => http.put(`${base}/${partnerPath(type)}/${id}`, body)
export const deletePartner = (type, id) => http.delete(`${base}/${partnerPath(type)}/${id}`)

/* ------------------------------------------------------------------ 单据 */

/** 六类单据共用同一套动作：列表 / 详情 / 提交 / 审核 / 驳回。 */
export const orderApi = {
  inbound: {
    label: '入库单',
    path: 'inbound-orders',
    editable: true,
    creatable: true,
  },
  outbound: {
    label: '出库单',
    path: 'outbound-orders',
    editable: true,
    creatable: true,
  },
  freeze: {
    label: '冻结与解冻单',
    path: 'freeze-orders',
    editable: false,
    creatable: true,
  },
  transfer: {
    label: '移库单',
    path: 'transfer-orders',
    editable: false,
    creatable: true,
  },
  stocktake: {
    label: '库存盘点单',
    path: 'stocktake-orders',
    editable: false,
    creatable: true,
  },
  adjust: {
    label: '库存调整单',
    path: 'adjust-orders',
    editable: false,
    creatable: false,
  },
}

export function listOrders(kind, params) {
  return http.get(`${base}/${orderApi[kind].path}`, { params: clean(params) })
}

export function getOrder(kind, id) {
  return http.get(`${base}/${orderApi[kind].path}/${id}`)
}

export function createOrder(kind, body) {
  return http.post(`${base}/${orderApi[kind].path}`, body)
}

export function updateOrder(kind, id, body) {
  return http.put(`${base}/${orderApi[kind].path}/${id}`, body)
}

export function deleteOrder(kind, id) {
  return http.delete(`${base}/${orderApi[kind].path}/${id}`)
}

export function submitOrder(kind, id) {
  return http.post(`${base}/${orderApi[kind].path}/${id}/submit`)
}

export function approveOrder(kind, id) {
  return http.post(`${base}/${orderApi[kind].path}/${id}/approve`)
}

export function rejectOrder(kind, id, reason) {
  return http.post(`${base}/${orderApi[kind].path}/${id}/reject`, { reason })
}

/* 盘点专用 */

export function countStocktake(id, items) {
  return http.put(`${base}/stocktake-orders/${id}/count`, { items })
}

export function completeStocktake(id) {
  return http.post(`${base}/stocktake-orders/${id}/complete`)
}

/* ------------------------------------------------------------------ 常量 */

export const ORDER_STATUS = {
  0: { label: '草稿', type: 'info' },
  1: { label: '待审核', type: 'warning' },
  2: { label: '已生效', type: 'success' },
  3: { label: '已驳回', type: 'danger' },
}

export const BIZ_TYPES = {
  INBOUND: '入库',
  OUTBOUND: '出库',
  FREEZE: '冻结',
  UNFREEZE: '解冻',
  TRANSFER_IN: '移入',
  TRANSFER_OUT: '移出',
  ADJUST: '调整',
}

export const AREA_TYPES = {
  RECEIVING: '收货区',
  STORAGE: '存储区',
  PICKING: '拣货区',
  DEFECTIVE: '不良品区',
}
