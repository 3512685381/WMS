import assert from 'node:assert/strict'
import test from 'node:test'
import { applyExecutionStep } from '../src/utils/agentTimeline.js'

function message() {
  return { steps: [], expert_count: 0, tool_call_count: 0 }
}

test('live expert and tool events retain facts and count paired calls once', () => {
  const reply = message()
  applyExecutionStep(reply, 'dispatch', { step_order: 1, agent_name: 'inventory_agent', task: '查询库存' })
  applyExecutionStep(reply, 'agent_start', { step_order: 2, agent_name: 'inventory_agent' })
  applyExecutionStep(reply, 'tool_start', {
    step_order: 3, agent_name: 'inventory_agent', tool_name: 'query_realtime_inventory',
    args: { warehouse: '中央仓', keyword: '充电器' },
  })
  assert.equal(reply.steps[2].status, 'running')
  assert.deepEqual(reply.steps[2].tool_args, { warehouse: '中央仓', keyword: '充电器' })
  applyExecutionStep(reply, 'tool_end', {
    step_order: 3, agent_name: 'inventory_agent', tool_name: 'query_realtime_inventory',
    summary: '命中 2 条库存记录', duration_ms: 142, status: 'success',
  })
  applyExecutionStep(reply, 'agent_end', { step_order: 4, agent_name: 'inventory_agent', duration_ms: 202 })
  applyExecutionStep(reply, 'dispatch', { step_order: 5, agent_name: 'alert_agent', task: '核对补货缺口' })
  assert.equal(reply.expert_count, 2)
  assert.equal(reply.tool_call_count, 1)
  assert.equal(reply.steps[0].result_summary, '查询库存')
  assert.equal(reply.steps[3].result_summary, '命中 2 条库存记录')
  assert.equal(reply.steps[3].duration_ms, 142)
})

test('updating an in-progress summary produces one replayable completed step', () => {
  const reply = message()
  applyExecutionStep(reply, 'summarize', {
    step_order: 8, agent_name: 'main_agent', result_summary: '正在汇总', status: 'running',
  })
  applyExecutionStep(reply, 'summarize', {
    step_order: 8, agent_name: 'main_agent', result_summary: '已汇总', status: 'success', duration_ms: 400,
  })
  assert.equal(reply.steps.length, 1)
  assert.equal(reply.steps[0].status, 'success')
  assert.equal(reply.steps[0].duration_ms, 400)
  assert.equal(reply.expert_count, 0)
})

test('duplicate tool events and unrelated SSE frames keep counters consistent', () => {
  const reply = message()
  const frame = { step_order: 3, agent_name: 'inventory_agent', tool_name: 'query_realtime_inventory' }
  applyExecutionStep(reply, 'tool_start', frame)
  applyExecutionStep(reply, 'tool_start', frame)
  assert.equal(reply.steps.length, 1)
  assert.equal(reply.tool_call_count, 1)
  assert.equal(applyExecutionStep(reply, 'token', { content: '回复' }), false)
  assert.equal(applyExecutionStep(reply, 'tool_end', { step_order: null }), false)
  assert.equal(reply.steps.length, 1)
})
