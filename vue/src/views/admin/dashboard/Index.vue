<script setup>
import { ArrowRight, ChatDotRound, Connection, DataAnalysis, Grid } from '@element-plus/icons-vue'
import { onMounted, onUnmounted, ref } from 'vue'
import { getAiHealth, listSessions } from '../../../api/ai'
import { usePermissionStore } from '../../../stores/permission'
import { useUserStore } from '../../../stores/user'
import { formatDateTime } from '../../../utils/format'

const permission = usePermissionStore()
const user = useUserStore()
const controller = new AbortController()
const health = ref('checking')
const recent = ref([])
const recentState = ref('loading')

onMounted(async () => {
  const signal = controller.signal
  await Promise.allSettled([
    getAiHealth(signal)
      .then((data) => { health.value = data.database === 'up' ? 'online' : 'offline' })
      .catch(() => { if (!signal.aborted) health.value = 'offline' }),
    ...(permission.agentPath ? [listSessions({ limit: 4, signal })
      .then((data) => { recent.value = data; recentState.value = 'ready' })
      .catch(() => { if (!signal.aborted) recentState.value = 'error' })] : []),
  ])
})
onUnmounted(() => controller.abort())
</script>

<template>
  <section class="page dashboard">
    <div class="page-heading">
      <h1>仓储看板</h1>
      <p>查看当前账号的功能入口与服务状态。</p>
    </div>

    <section class="workspace-hero">
      <div class="workspace-hero-content">
        <h2>你好，{{ user.displayName }}</h2>
        <p v-if="permission.agentPath">回看仓储助手的对话、分析结果与执行步骤。</p>
        <p v-else>从左侧菜单进入已授权的仓储模块。</p>
        <RouterLink v-if="permission.agentPath" :to="permission.agentPath" class="link-button hero-link">
          打开 AI 助手 <ArrowRight class="icon" aria-hidden="true" />
        </RouterLink>
      </div>
    </section>

    <div class="dashboard-metrics">
      <div class="metric surface">
        <span class="metric-icon"><Grid class="icon" aria-hidden="true" /></span>
        <div><span>已授权页面</span><strong>{{ permission.pages.length }}</strong></div>
      </div>
      <div class="metric surface">
        <span class="metric-icon metric-blue"><Connection class="icon" aria-hidden="true" /></span>
        <div><span>Spring Boot</span><strong class="metric-text"><span class="status-dot online" aria-hidden="true"></span>鉴权已连接</strong></div>
      </div>
      <div class="metric surface">
        <span class="metric-icon metric-amber"><DataAnalysis class="icon" aria-hidden="true" /></span>
        <div>
          <span>FastAPI</span>
          <strong class="metric-text">
            <span class="status-dot" :class="health" aria-hidden="true"></span>
            {{ { checking: '检查中', online: '数据库已连接', offline: '连接不可用' }[health] }}
          </strong>
        </div>
      </div>
    </div>

    <div class="dashboard-detail">
      <section v-if="permission.agentPath" class="surface dashboard-card" aria-labelledby="recent-heading">
        <div class="card-heading">
          <h2 id="recent-heading">最近的 AI 会话</h2>
          <RouterLink :to="permission.agentPath" class="text-link">查看全部 <ArrowRight class="icon" aria-hidden="true" /></RouterLink>
        </div>
        <p v-if="recentState === 'loading'" class="card-feedback" role="status">正在加载会话…</p>
        <p v-else-if="recentState === 'error'" class="card-feedback" role="alert">会话暂时无法加载。</p>
        <p v-else-if="!recent.length" class="card-feedback">暂无会话。</p>
        <ul v-else class="recent-list">
          <li v-for="session in recent" :key="session.session_id">
            <RouterLink :to="{ path: permission.agentPath, query: { session: session.session_id } }">
              <span class="recent-icon"><ChatDotRound class="icon" aria-hidden="true" /></span>
              <span class="recent-text"><strong>{{ session.title }}</strong><time :datetime="session.updated_at">{{ formatDateTime(session.updated_at) }}</time></span>
              <ArrowRight class="icon recent-arrow" aria-hidden="true" />
            </RouterLink>
          </li>
        </ul>
      </section>
      <section class="surface dashboard-card inventory-card" aria-labelledby="inventory-heading">
        <div class="card-heading"><h2 id="inventory-heading">库存总览</h2></div>
        <div class="card-feedback inventory-feedback">
          <span class="metric-icon"><DataAnalysis class="icon" aria-hidden="true" /></span>
          <div><strong>库存看板接口待接入</strong><p>接入后可查看库存规模、出入库趋势与预警。</p></div>
        </div>
      </section>
    </div>
  </section>
</template>
