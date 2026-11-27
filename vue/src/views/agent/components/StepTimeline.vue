<script setup>
import { ArrowDown, Loading, Operation } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import { formatDuration } from '../../../utils/format'

const props = defineProps({ message: { type: Object, required: true } })
const expanded = ref(false)
const running = computed(() => props.message.run_status === 'running')

watch(() => props.message.run_status, (status) => {
  expanded.value = ['running', 'interrupted', 'error'].includes(status)
}, { immediate: true })

const agentLabels = {
  main_agent: '调度主管',
  inventory_agent: '库存查询专家',
  order_agent: '单据管理专家',
  alert_agent: '预警分析专家',
  stats_agent: '统计分析专家',
}
const eventLabels = {
  dispatch: '派发任务',
  agent_start: '专家开始',
  agent_end: '专家完成',
  tool_start: '调用工具',
  tool_end: '调用工具',
  summarize: '汇总结果',
}
const statusLabels = { running: '执行中', success: '成功', error: '失败', interrupted: '已中断' }

const steps = computed(() => {
  const rows = []
  const started = new Map()
  const agents = new Map()
  for (const step of props.message.steps || []) {
    const key = JSON.stringify([step.step_order, step.agent_name, step.tool_name])
    if (step.event_type === 'agent_start') {
      const pending = agents.get(step.agent_name) || []
      pending.push(rows.length)
      agents.set(step.agent_name, pending)
    } else if (step.event_type === 'agent_end') {
      const index = agents.get(step.agent_name)?.shift()
      if (index !== undefined) rows[index] = { ...rows[index], status: step.status }
    }
    if (step.event_type === 'tool_end' && started.has(key)) {
      const index = started.get(key)
      const start = rows[index]
      rows[index] = { ...step, tool_args: step.tool_args ?? start.tool_args }
      started.delete(key)
    } else {
      if (step.event_type === 'tool_start') started.set(key, rows.length)
      rows.push(step)
    }
  }
  return rows.map((step) => {
    if (step.status !== 'running' || running.value) return step
    return { ...step, status: props.message.run_status === 'interrupted' ? 'interrupted' : 'error' }
  })
})
</script>

<template>
  <div class="timeline">
    <button class="timeline-toggle" type="button" :aria-expanded="expanded" :aria-controls="'steps-' + message.id" @click="expanded = !expanded">
      <Operation class="icon" aria-hidden="true" />
      <span>
        <strong>执行过程</strong>
        <span class="timeline-count" aria-live="polite">已协同 {{ message.expert_count ?? 0 }} 位专家 · {{ message.tool_call_count ?? 0 }} 次工具调用 · {{ running ? '执行中' : formatDuration(message.total_duration_ms ?? 0) }}</span>
      </span>
      <ArrowDown class="icon timeline-arrow" aria-hidden="true" />
    </button>
    <div v-show="expanded" :id="'steps-' + message.id">
      <p v-if="!steps.length" class="timeline-wait" role="status"><Loading class="icon timeline-loading" aria-hidden="true" />调度主管正在分析任务…</p>
      <ol v-else class="timeline-list" :aria-busy="running">
        <li v-for="step in steps" :key="step.id" class="timeline-step" :class="'step-' + step.status">
          <div class="step-title">
            <Loading v-if="step.status === 'running' && running" class="icon timeline-loading" aria-hidden="true" />
            <strong>{{ eventLabels[step.event_type] || step.event_type }}</strong>
            <span v-if="step.agent_name">{{ agentLabels[step.agent_name] || step.agent_name }}</span>
            <span v-if="step.duration_ms != null" class="step-time">{{ formatDuration(step.duration_ms) }}</span>
            <span v-if="step.event_type.startsWith('tool_') || step.event_type === 'summarize' || ['error', 'interrupted'].includes(step.status)" class="step-status">{{ statusLabels[step.status] }}</span>
          </div>
          <code v-if="step.tool_name" class="step-tool">{{ step.tool_name }}</code>
          <p v-if="step.result_summary">{{ step.result_summary }}</p>
          <details v-if="step.tool_args != null" class="step-args">
            <summary>工具参数</summary>
            <pre>{{ JSON.stringify(step.tool_args, null, 2) }}</pre>
          </details>
        </li>
      </ol>
    </div>
  </div>
</template>
