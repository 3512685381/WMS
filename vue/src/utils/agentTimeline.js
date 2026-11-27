const stepEvents = new Set([
  'dispatch', 'agent_start', 'tool_start', 'tool_end', 'agent_end', 'summarize',
])

export function applyExecutionStep(message, event, data) {
  if (!stepEvents.has(event) || !Number.isInteger(data.step_order) || data.step_order < 1) return false

  const index = message.steps.findIndex((step) =>
    step.step_order === data.step_order && step.event_type === event)
  const previous = index === -1 ? undefined : message.steps[index]
  const step = {
    ...previous,
    ...data,
    id: previous?.id ?? data.id ?? data.step_order + ':' + event,
    event_type: event,
    tool_args: data.tool_args ?? data.args ?? previous?.tool_args ?? null,
    result_summary: data.result_summary ?? data.summary ?? data.task ?? previous?.result_summary ?? null,
    duration_ms: data.duration_ms ?? previous?.duration_ms ?? null,
    status: data.status ?? (event.endsWith('_start') ? 'running' : 'success'),
  }
  if (index === -1) message.steps.push(step)
  else message.steps.splice(index, 1, step)

  const experts = message.steps
    .filter((entry) => entry.agent_name && entry.agent_name !== 'main_agent')
    .map((entry) => entry.agent_name)
  const calls = message.steps
    .filter((entry) => entry.event_type.startsWith('tool_'))
    .map((entry) => entry.step_order)
  message.expert_count = new Set(experts).size
  message.tool_call_count = new Set(calls).size
  return true
}
