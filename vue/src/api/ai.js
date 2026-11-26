import http, { notifyUnauthorized, readToken } from './request'

const sessionPath = (id) => '/ai/sessions/' + encodeURIComponent(id)

export const getAiHealth = (signal) => http.get('/ai/health', { anonymous: true, signal })
export const listSessions = ({ limit = 30, offset = 0, signal } = {}) =>
  http.get('/ai/sessions', { params: { limit, offset }, signal })
export const createSession = (title) => http.post('/ai/sessions', title ? { title } : {})
export const renameSession = (id, title) => http.put(sessionPath(id), { title })
export const deleteSession = (id) => http.delete(sessionPath(id))
export const getMessages = (id, signal) => http.get(sessionPath(id) + '/messages', { signal })
export const getModelOptions = (signal) => http.get('/ai/chat/models', { signal })

export async function streamChat({ sessionId, query, model, signal, onEvent }) {
  const token = readToken()
  const response = await fetch('/ai/chat/stream', {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
      Authorization: 'Bearer ' + token,
    },
    body: JSON.stringify({ session_id: sessionId, query, model: model || undefined }),
    signal,
  })
  if (!response.ok) {
    if (response.status === 401) notifyUnauthorized(token)
    const payload = await response.json().catch(() => null)
    throw new Error(payload?.msg || 'AI 服务暂时不可用，请检查 FastAPI 服务。')
  }
  if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) {
    throw new Error('聊天接口未返回流式数据，请检查 FastAPI 服务。')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    while (true) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value, { stream: !done })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop()
      for (const frame of frames) {
        const lines = frame.split(/\r?\n/)
        const event = lines.find((line) => line.startsWith('event:'))?.slice(6).trim()
        const data = lines.filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).replace(/^ /, '')).join('\n')
        if (!event || !data) continue
        const payload = JSON.parse(data)
        if (event === 'error') throw new Error(payload.message || '模型未能完成本轮回复。')
        onEvent(event, payload)
        if (event === 'done') return
      }
      if (done) throw new Error('回复连接提前断开，请重试。')
    }
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
