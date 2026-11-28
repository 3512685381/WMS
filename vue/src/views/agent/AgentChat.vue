<script setup>
import { ChatDotRound, Cpu, Delete, EditPen, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createSession, deleteSession, getMessages, getModelOptions, getRun, listSessions, renameSession, streamChat } from '../../api/ai'
import { applyExecutionStep } from '../../utils/agentTimeline'
import { formatDateTime, formatShortDate } from '../../utils/format'
import { useUserStore } from '../../stores/user'
import MarkdownViewer from './components/MarkdownViewer.vue'
import StepTimeline from './components/StepTimeline.vue'
import './agent.css'

const router = useRouter()
const route = useRoute()
const user = useUserStore()

const pageSize = 30
const uuid = /^[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}$/i
const sessions = ref([])
const messages = ref([])
const sessionError = ref('')
const messageError = ref('')
const readingList = ref(false)
const readingMessages = ref(false)
const mutating = ref(false)
const nextOffset = ref(0)
const hasMore = ref(false)
const historyVersion = ref(0)
const scroller = ref(null)
const draft = ref('')
const sending = ref(false)
const chatError = ref('')
const modelOptions = ref([])
const selectedModel = ref('')
const loadingModels = ref(true)
const modelError = ref('')
const modelRequest = new AbortController()

const selectedId = computed(() => {
  const id = route.query.session
  return typeof id === 'string' && uuid.test(id) ? id.toLowerCase() : ''
})
const current = computed(() => sessions.value.find((session) => session.session_id === selectedId.value))
const runLabels = { running: '执行中', error: '执行失败', interrupted: '已中断' }
const RESUME_INTERVAL_MS = 2000
const RESUME_MAX_ATTEMPTS = 150
const lastRequest = ref(null)
let listRequest
let chatRequest
let disposed = false

function newRequestId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return 'req-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
}

// 页面刷新或断线后读取已有运行：已完成的直接回放文本、步骤和草稿，不重新执行。
async function resumeRun(message) {
  const runId = message.run_id
  const sessionId = selectedId.value
  if (!runId || !sessionId) return
  for (let attempt = 0; attempt < RESUME_MAX_ATTEMPTS; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, RESUME_INTERVAL_MS))
    if (disposed || selectedId.value !== sessionId || message.run_status !== 'running') return
    let run
    try {
      run = await getRun(runId)
    } catch {
      return
    }
    if (disposed || selectedId.value !== sessionId) return
    message.content = run.content
    message.steps = run.steps
    message.drafts = run.drafts
    message.run_status = run.status
    message.expert_count = run.expert_count
    message.tool_call_count = run.tool_call_count
    message.total_duration_ms = run.total_duration_ms
    if (run.status !== 'running') {
      scrollToLatest()
      return
    }
  }
}

function selectSession(id, replace = false) {
  if (disposed) return
  const query = { ...route.query }
  if (id) query.session = id
  else delete query.session
  const to = { path: route.path, query }
  return replace ? router.replace(to) : router.push(to)
}

async function loadSessions({ more = false } = {}) {
  listRequest?.abort()
  const controller = new AbortController()
  listRequest = controller
  const { signal } = controller
  readingList.value = true
  sessionError.value = ''
  try {
    const offset = more ? nextOffset.value : 0
    const data = await listSessions({ limit: pageSize, offset, signal })
    if (signal.aborted) return
    const prior = more ? sessions.value : []
    const known = new Set(prior.map((session) => session.session_id))
    sessions.value = [...prior, ...data.filter((session) => !known.has(session.session_id))]
    nextOffset.value = offset + data.length
    hasMore.value = data.length === pageSize
    if (!selectedId.value && sessions.value.length) await selectSession(sessions.value[0].session_id, true)
  } catch (error) {
    if (!signal.aborted) sessionError.value = error.message
  } finally {
    if (!signal.aborted) readingList.value = false
  }
}

watch([selectedId, historyVersion], async ([id], [previousId], onCleanup) => {
  if (id !== previousId) {
    chatRequest?.abort()
    draft.value = ''
    chatError.value = ''
  }
  const controller = new AbortController()
  const { signal } = controller
  onCleanup(() => controller.abort())
  messageError.value = ''
  messages.value = []
  readingMessages.value = Boolean(id)
  if (scroller.value) scroller.value.scrollTop = 0
  if (!id) return

  try {
    const history = await getMessages(id, signal)
    if (signal.aborted) return
    messages.value = history
    const running = [...messages.value].reverse().find(
      (message) => message.role === 'assistant' && message.run_status === 'running' && message.run_id,
    )
    if (running) void resumeRun(running)
  } catch (error) {
    if (!signal.aborted) messageError.value = error.message
  } finally {
    if (!signal.aborted) readingMessages.value = false
  }
}, { immediate: true })

async function updateSession(action) {
  if (mutating.value) return
  mutating.value = true
  try {
    await action()
  } catch (error) {
    if (!disposed && error !== 'cancel' && error !== 'close') {
      ElMessage.error(error.message || '操作未完成，请重试。')
    }
  } finally {
    mutating.value = false
  }
}

function add() {
  return updateSession(async () => {
    const result = await createSession()
    if (disposed) return
    await selectSession(result.session_id)
    await loadSessions()
  })
}

function edit() {
  const session = current.value
  if (!session) return
  return updateSession(async () => {
    const { value } = await ElMessageBox.prompt('输入新的会话名称。', '重命名会话', {
      inputValue: session.title,
      inputPlaceholder: '会话名称',
      inputValidator(title) {
        const length = Array.from(title?.trim() || '').length
        return length >= 1 && length <= 160 ? true : '请输入 1 到 160 个字符'
      },
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    })
    if (disposed) return
    await renameSession(session.session_id, value.trim())
    if (!disposed) await loadSessions()
  })
}

function remove() {
  const session = current.value
  if (!session) return
  return updateSession(async () => {
    await ElMessageBox.confirm(
      '确定删除「' + session.title + '」？该会话的消息和执行记录也会删除。',
      '删除会话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
    if (disposed) return
    await deleteSession(session.session_id)
    if (disposed) return
    sessions.value = sessions.value.filter((entry) => entry.session_id !== session.session_id)
    if (selectedId.value === session.session_id) {
      await selectSession(sessions.value[0]?.session_id, true)
    }
    await loadSessions()
  })
}

async function loadModels() {
  const { signal } = modelRequest
  try {
    const data = await getModelOptions(signal)
    if (!signal.aborted) {
      modelOptions.value = data.models
      selectedModel.value = data.default_model
    }
  } catch (error) {
    if (!signal.aborted) modelError.value = error.message
  } finally {
    if (!signal.aborted) loadingModels.value = false
  }
}

function scrollToLatest() {
  void nextTick(() => {
    if (scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight
  })
}

async function send(retryPayload = null) {
  const sessionId = selectedId.value
  const retrying = retryPayload && typeof retryPayload === 'object' && retryPayload.clientRequestId
  const query = retrying ? retryPayload.query : draft.value.trim()
  if (!sessionId || !query || sending.value || readingMessages.value || mutating.value || messageError.value) return

  // 同一次发送的网络重试沿用同一标识；服务端据此回放结果，不会重复建单。
  const clientRequestId = retrying ? retryPayload.clientRequestId : newRequestId()
  lastRequest.value = { query, clientRequestId }
  const controller = new AbortController()
  chatRequest = controller
  const { signal } = controller
  sending.value = true
  chatError.value = ''
  const started = performance.now()
  let answer
  try {
    const onEvent = (event, data) => {
      if (signal.aborted || selectedId.value !== sessionId) return
      if (event === 'start') {
        const existing = data.replayed
          ? messages.value.find((message) => message.id === data.message_id)
          : null
        if (existing) {
          existing.content = ''
          existing.steps = []
          existing.drafts = []
          existing.expert_count = 0
          existing.tool_call_count = 0
          existing.total_duration_ms = 0
          existing.run_status = data.status || 'running'
          answer = existing
          scrollToLatest()
        } else {
          const created_at = new Date().toISOString()
          answer = reactive({
            id: data.message_id,
            role: 'assistant',
            content: '',
            created_at,
            run_id: data.run_id,
            run_status: data.status || 'running',
            steps: [],
            drafts: [],
            expert_count: 0,
            tool_call_count: 0,
            total_duration_ms: 0,
          })
          messages.value.push({ id: data.user_message_id, role: 'user', content: query, created_at }, answer)
          draft.value = ''
          scrollToLatest()
        }
      } else if (answer) {
        const element = scroller.value
        const nearBottom = element && element.scrollHeight - element.scrollTop - element.clientHeight < 90
        if (event === 'token') {
          answer.content += data.content
        } else if (event === 'done') {
          answer.expert_count = data.expert_count
          answer.tool_call_count = data.tool_call_count
          answer.total_duration_ms = data.total_duration_ms
          answer.drafts = data.drafts || []
          if (answer.run_status === 'running') answer.run_status = 'success'
          lastRequest.value = null
        } else {
          applyExecutionStep(answer, event, data)
        }
        if (nearBottom) scrollToLatest()
      }
    }
    await streamChat({
      sessionId, query, model: selectedModel.value, clientRequestId, signal, onEvent,
    })
  } catch (error) {
    if (answer) answer.run_status = signal.aborted ? 'interrupted' : 'error'
    if (signal.aborted) lastRequest.value = null
    if (!disposed && !signal.aborted && selectedId.value === sessionId) {
      chatError.value = error.message || '发送失败，请重试。'
    }
  } finally {
    if (answer && answer.run_status !== 'success') {
      answer.total_duration_ms = Math.round(performance.now() - started)
      if (answer.run_status === 'running') answer.run_status = signal.aborted ? 'interrupted' : 'error'
    }
    sending.value = false
    chatRequest = undefined
    if (answer && !disposed) void loadSessions()
  }
}

function retry() {
  if (!lastRequest.value || sending.value) return
  void send(lastRequest.value)
}

function stop() {
  chatRequest?.abort()
}

function sendOnEnter(event) {
  if (event.isComposing || event.shiftKey) return
  event.preventDefault()
  void send()
}

onBeforeUnmount(() => {
  disposed = true
  listRequest?.abort()
  modelRequest.abort()
  stop()
})
void loadSessions()
void loadModels()
</script>

<template>
  <section class="page agent-page">
    <div class="page-heading">
      <h1>{{ $route.meta.title }}</h1>
      <p>向仓储助手提问，查看对话历史与执行记录。</p>
    </div>

    <div class="surface agent-workspace">
      <aside class="session-rail" aria-label="历史会话">
        <div class="session-header">
          <h2>历史会话</h2>
          <el-button type="primary" :icon="Plus" :disabled="mutating" @click="add">新建</el-button>
        </div>
        <div class="session-scroll">
          <div v-if="sessionError" class="session-feedback" role="alert">
            <p>{{ sessionError }}</p>
            <button type="button" class="text-link" @click="loadSessions()">重试</button>
          </div>
          <p v-else-if="readingList && !sessions.length" class="session-feedback" role="status">加载会话中…</p>
          <p v-else-if="!sessions.length" class="session-feedback">暂无会话。</p>
          <ul v-if="sessions.length" class="session-list">
            <li v-for="session in sessions" :key="session.session_id">
              <button
                type="button"
                :class="{ 'session-active': session.session_id === selectedId }"
                :aria-current="session.session_id === selectedId ? 'true' : undefined"
                :title="session.title"
                @click="selectSession(session.session_id)"
              >
                <ChatDotRound class="icon" aria-hidden="true" />
                <span class="session-title">{{ session.title }}</span>
                <time :datetime="session.updated_at">{{ formatShortDate(session.updated_at) }}</time>
              </button>
            </li>
          </ul>
          <button v-if="hasMore && !sessionError" type="button" class="text-link load-more" :disabled="readingList" @click="loadSessions({ more: true })">
            {{ readingList ? '加载中…' : '加载更多会话' }}
          </button>
        </div>
      </aside>

      <section class="conversation" aria-label="会话内容">
        <header class="conversation-header">
          <span class="conversation-icon"><Cpu class="icon" aria-hidden="true" /></span>
          <div class="conversation-title">
            <h2>{{ current?.title || (selectedId ? '会话详情' : '仓储助手') }}</h2>
            <span v-if="current">更新于 {{ formatDateTime(current.updated_at) }}</span>
          </div>
          <div v-if="current" class="conversation-actions">
            <button type="button" class="icon-button" title="重命名会话" aria-label="重命名会话" :disabled="mutating" @click="edit"><EditPen class="icon" aria-hidden="true" /></button>
            <button type="button" class="icon-button danger-button" title="删除会话" aria-label="删除会话" :disabled="mutating || sending" @click="remove"><Delete class="icon" aria-hidden="true" /></button>
          </div>
        </header>

        <div ref="scroller" class="conversation-scroll" :aria-busy="readingMessages">
          <div v-if="messageError" class="conversation-empty" role="alert">
            <span class="empty-icon"><ChatDotRound class="icon" aria-hidden="true" /></span>
            <h3>会话加载失败</h3>
            <p>{{ messageError }}</p>
            <el-button @click="historyVersion++">重新加载</el-button>
          </div>
          <p v-else-if="readingMessages" class="conversation-empty" role="status">正在加载消息…</p>
          <div v-else-if="!messages.length" class="conversation-empty">
            <span class="empty-icon"><ChatDotRound class="icon" aria-hidden="true" /></span>
            <h3>{{ selectedId ? '这段会话还没有消息' : '选择一段会话' }}</h3>
            <p v-if="!selectedId">选择已有会话或新建会话后即可发送消息。</p>
          </div>

          <div v-else class="message-list">
            <article v-for="message in messages" :key="message.id" class="message" :class="'message-' + message.role">
              <span v-if="message.role === 'assistant'" class="message-avatar"><Cpu class="icon" aria-hidden="true" /></span>
              <span v-else class="message-avatar user-message-avatar">{{ user.initial }}</span>
              <div class="message-main">
                <div class="message-meta">
                  <strong>{{ message.role === 'assistant' ? '仓储助手' : '你' }}</strong>
                  <time :datetime="message.created_at">{{ formatDateTime(message.created_at) }}</time>
                  <span v-if="runLabels[message.run_status]" class="message-run" :class="'run-' + message.run_status">{{ runLabels[message.run_status] }}</span>
                </div>
                <StepTimeline v-if="message.role === 'assistant' && (message.steps?.length || message.run_status === 'running')" :message="message" />
                <template v-if="message.role === 'assistant'">
                  <MarkdownViewer v-if="message.content" :content="message.content" />
                  <p v-else class="message-placeholder">{{ message.run_status === 'running' ? '正在生成回复…' : '本轮没有生成回复。' }}</p>
                  <div v-if="message.drafts?.length" class="draft-cards">
                    <article v-for="draft in message.drafts" :key="draft.order_id" class="draft-card">
                      <div class="draft-card-head">
                        <span class="draft-kind">{{ draft.order_type === 'inbound' ? '入库草稿' : '出库草稿' }}</span>
                        <strong>{{ draft.order_no }}</strong>
                        <span class="draft-status">{{ draft.status_label }}</span>
                      </div>
                      <p>AI 代建，共 {{ draft.item_count }} 项明细。审核生效前不会扣减库存，请到单据审核页面核对。</p>
                    </article>
                  </div>
                </template>
                <p v-else class="user-content">{{ message.content }}</p>
              </div>
            </article>
          </div>
        </div>

        <form class="chat-composer" aria-label="发送消息" @submit.prevent="send">
          <div v-if="chatError" class="chat-composer-error" role="alert">
            <span>{{ chatError }}</span>
            <button v-if="lastRequest && !sending" type="button" class="text-link" @click="retry">重试</button>
          </div>
          <textarea
            v-model="draft"
            class="chat-input"
            rows="2"
            maxlength="4000"
            aria-label="消息内容"
            :placeholder="selectedId ? '向仓储助手提问' : '选择或新建会话'"
            :disabled="!selectedId || sending || Boolean(messageError)"
            @keydown.enter="sendOnEnter"
          ></textarea>
          <div class="chat-composer-row">
            <label class="chat-model-picker" :title="modelError || selectedModel">
              <span>模型</span>
              <select v-model="selectedModel" :disabled="sending || modelOptions.length < 2">
                <option v-if="!modelOptions.length" value="">{{ loadingModels ? '加载中…' : '默认模型' }}</option>
                <option v-for="model in modelOptions" :key="model.id" :value="model.id">{{ model.name }}</option>
              </select>
            </label>
            <el-button v-if="sending" size="large" @click="stop">停止生成</el-button>
            <el-button
              v-else
              type="primary"
              size="large"
              native-type="submit"
              :disabled="!selectedId || !draft.trim() || readingMessages || mutating || Boolean(messageError)"
            >发送</el-button>
          </div>
        </form>
      </section>
    </div>
  </section>
</template>
