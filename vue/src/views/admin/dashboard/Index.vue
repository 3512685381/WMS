<script setup>
import { ArrowRight, ChatDotRound, Connection, DataAnalysis, Grid, Warning } from '@element-plus/icons-vue'
import { onMounted, onUnmounted, ref } from 'vue'
import { getAiHealth, listSessions } from '../../../api/ai'
import { getDashboardSummary } from '../../../api/wms'
import { usePermissionStore } from '../../../stores/permission'
import { useUserStore } from '../../../stores/user'
import { formatDateTime } from '../../../utils/format'

const permission = usePermissionStore()
const user = useUserStore()
const controller = new AbortController()
const health = ref('checking')
const recent = ref([])
const recentState = ref('loading')
const summary = ref(null)
const summaryState = ref('loading')

const canDashboard = permission.pages.some((page) => page.perms === 'wms:dashboard:view')

onMounted(async () => {
  const signal = controller.signal
  await Promise.allSettled([
    getAiHealth(signal)
      .then((data) => { health.value = data.database === 'up' ? 'online' : 'offline' })
      .catch(() => { if (!signal.aborted) health.value = 'offline' }),
    ...(permission.agentPath ? [listSessions({ limit: 4, signal })
      .then((data) => { recent.value = data; recentState.value = 'ready' })
      .catch(() => { if (!signal.aborted) recentState.value = 'error' })] : []),
    ...(canDashboard ? [getDashboardSummary()
      .then((data) => { summary.value = data; summaryState.value = 'ready' })
      .catch(() => { if (!signal.aborted) summaryState.value = 'error' })] : []),
  ])
})
onUnmounted(() => controller.abort())

const number = (value) => (value === null || value === undefined ? '—' : Number(value).toLocaleString('zh-CN', {
  maximumFractionDigits: 3,
}))
</script>

<template>
  <section class="page dashboard">
    <div class="page-heading">
      <h1>仓储看板</h1>
      <p>查看库存规模、待审核单据与预警情况。</p>
    </div>

    <section class="workspace-hero">
      <div class="workspace-hero-content">
        <h2>你好，{{ user.displayName }}</h2>
        <p v-if="permission.agentPath">用自然语言建单：AI 助手生成的草稿仍需人工审核后才会记账。</p>
        <p v-else>从左侧菜单进入已授权的仓储模块。</p>
        <RouterLink v-if="permission.agentPath" :to="permission.agentPath" class="link-button hero-link">
          打开 AI 助手 <ArrowRight class="icon" aria-hidden="true" />
        </RouterLink>
      </div>
    </section>

    <div class="wms-metrics">
      <div class="surface wms-metric">
        <span>仓库 / 商品</span>
        <strong>{{ number(summary?.warehouseCount) }} <small>/ {{ number(summary?.productCount) }}</small></strong>
      </div>
      <div class="surface wms-metric">
        <span>库存总量</span>
        <strong>{{ number(summary?.totalQuantity) }}</strong>
      </div>
      <div class="surface wms-metric">
        <span>冻结数量</span>
        <strong>{{ number(summary?.totalFrozenQuantity) }}</strong>
      </div>
      <div class="surface wms-metric">
        <span>库存金额（元）</span>
        <strong>{{ number(summary?.inventoryValue) }}</strong>
      </div>
      <div class="surface wms-metric">
        <span>待审核入库</span>
        <strong>{{ number(summary?.pendingInbound) }}</strong>
      </div>
      <div class="surface wms-metric">
        <span>待审核出库</span>
        <strong>{{ number(summary?.pendingOutbound) }}</strong>
      </div>
    </div>

    <div class="dashboard-detail">
      <section class="surface dashboard-card" aria-labelledby="alert-heading">
        <div class="card-heading">
          <h2 id="alert-heading">库存预警</h2>
          <RouterLink v-if="permission.pages.some((page) => page.perms === 'wms:alert:list')" to="/admin/alerts" class="text-link">
            查看全部 <ArrowRight class="icon" aria-hidden="true" />
          </RouterLink>
        </div>
        <p v-if="summaryState === 'loading'" class="card-feedback" role="status">正在加载看板数据…</p>
        <p v-else-if="summaryState === 'error'" class="card-feedback" role="alert">看板数据暂时无法加载。</p>
        <div v-else class="dashboard-metrics" style="margin-top: 12px">
          <div class="metric surface">
            <span class="metric-icon metric-amber"><Warning class="icon" aria-hidden="true" /></span>
            <div><span>低库存商品</span><strong>{{ number(summary?.lowStockCount) }}</strong></div>
          </div>
          <div class="metric surface">
            <span class="metric-icon metric-blue"><DataAnalysis class="icon" aria-hidden="true" /></span>
            <div><span>临期批次</span><strong>{{ number(summary?.expiringCount) }}</strong></div>
          </div>
          <div class="metric surface">
            <span class="metric-icon"><Grid class="icon" aria-hidden="true" /></span>
            <div><span>已授权页面</span><strong>{{ permission.pages.length }}</strong></div>
          </div>
        </div>
        <p class="card-feedback">
          低库存按商品安全库存比较，临期按 30 天内失效的批次统计，与 AI 助手的口径一致。
        </p>
      </section>

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

      <section v-else class="surface dashboard-card">
        <div class="card-heading"><h2>服务状态</h2></div>
        <p class="card-feedback">
          <span class="status-dot online" aria-hidden="true"></span>
          鉴权服务已连接
        </p>
        <p class="card-feedback">
          <span class="status-dot" :class="health" aria-hidden="true"></span>
          {{ { checking: 'AI 服务检查中', online: 'AI 服务数据库已连接', offline: 'AI 服务连接不可用' }[health] }}
        </p>
        <p class="card-feedback"><Connection class="icon" aria-hidden="true" /> Spring Boot 仓储接口已启用</p>
      </section>
    </div>
  </section>
</template>
