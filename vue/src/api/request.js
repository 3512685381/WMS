import axios from 'axios'

const tokenKey = 'wms.access-token'
let activeToken = localStorage.getItem(tokenKey) || ''

export function readToken() {
  return activeToken
}

export function writeToken(token) {
  if (token) localStorage.setItem(tokenKey, token)
  else if (localStorage.getItem(tokenKey) === activeToken) localStorage.removeItem(tokenKey)
  activeToken = token
}

class ApiError extends Error {
  constructor(message, status) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

let unauthorized = () => {}

export function onUnauthorized(handler) {
  unauthorized = handler
}

export function notifyUnauthorized(token) {
  if (token) unauthorized(token)
}

const http = axios.create({ timeout: 12000 })

http.interceptors.request.use((config) => {
  const token = config.anonymous ? '' : readToken()
  config.wmsToken = token
  if (token) config.headers.set('Authorization', 'Bearer ' + token)
  return config
})

function rejectResponse(status, payload, config) {
  if (status === 401 && config?.wmsToken && !config.ignoreUnauthorized) {
    notifyUnauthorized(config.wmsToken)
  }

  let message = payload?.msg || '请求未完成，请重试。'
  if (status >= 500) message = '服务暂时不可用，请确认后端已启动。'
  throw new ApiError(message, status)
}

http.interceptors.response.use((response) => {
  const payload = response.data
  if (payload?.code === 200) return payload.data
  if (typeof payload?.code === 'number') {
    return rejectResponse(payload.code, payload, response.config)
  }
  throw new ApiError('服务器响应格式异常，请检查代理配置。', response.status)
}, (error) => {
  if (axios.isCancel(error)) throw error
  if (error.response) {
    return rejectResponse(error.response.status, error.response.data, error.config)
  }
  const message = error.code === 'ECONNABORTED'
    ? '请求超时，请重试。'
    : '无法连接服务，请检查网络与后端状态。'
  throw new ApiError(message, 0)
})

export default http
