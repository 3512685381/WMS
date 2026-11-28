# AI 多 Agent 接口与事件契约

本文件是 FastAPI 服务的实现基线：接口、SSE 事件、写操作状态机、权限矩阵与验收用例。
后端代码、前端联调和跨服务测试都以这里为准；修改契约需同步更新本文与 `fastapi/README.md`。

## 1. 职责边界

| 层次 | 负责 | 不负责 |
| --- | --- | --- |
| 调度主管（`main_agent`） | 理解请求、拆分任务、协调专家、汇总事实 | 直接查库、直接建单 |
| 专家与工具（4 位专家 / 14 个工具） | 查询业务数据、校验参数、创建允许的草稿 | 修改库存、审核单据、写流水 |
| 服务端策略（`agent/policy.py`） | 判定本轮操作、装配工具、执行前放行 | 依赖模型判断是否授权 |
| Spring Boot | 提交、审核、驳回、库存与流水事务 | 生成对话、调用模型 |

Python 侧没有任何 `wms_inventory` 的 `INSERT/UPDATE/DELETE` 能力，只读工具在 `SET TRANSACTION READ ONLY` 事务内执行。

## 2. HTTP 接口

统一响应 `{"code":200,"msg":"success","data":...}`；错误沿用同一结构并返回对应 HTTP 状态码。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/ai/sessions?limit&offset` | 本人会话列表，按 `updated_at` 倒序 |
| POST | `/ai/sessions` | 新建会话，`{"title": "..."}` 可选 |
| PUT | `/ai/sessions/{id}` | 改名 |
| DELETE | `/ai/sessions/{id}` | 删除会话，级联消息、运行、步骤、草稿回执 |
| GET | `/ai/sessions/{id}/messages?limit&before_id` | 最近消息与步骤回放；`before_id` 续查更早历史；返回 `drafts` |
| GET | `/ai/chat/models` | 网关可用模型列表 |
| POST | `/ai/chat/stream` | SSE 对话 |
| GET | `/ai/runs/{run_id}` | 运行详情：状态、文本、步骤、草稿；页面重连读取，不重新执行 |
| POST | `/ai/runs/{run_id}/cancel` | 收敛失去执行者的运行；仍有心跳时返回 409 |

### 2.1 `POST /ai/chat/stream`

```json
{
  "session_id": "会话 UUID",
  "query": "帮我在中央仓创建出库草稿：20W 充电器 5 件，批次 B001",
  "model": "可选模型标识",
  "client_request_id": "可选，8-64 位 [A-Za-z0-9_.:-]"
}
```

`client_request_id` 是请求级幂等键：同一次发送的网络重试必须保持不变。相同标识重复提交时：

- 上一轮已结束（`success`/`error`/`interrupted`）：不重新执行，回放已保存文本与草稿卡片；
- 上一轮仍在执行：返回 `409`，客户端应通过 `/ai/runs/{run_id}` 读取进度；
- 未传该字段：服务端每次生成新的运行记录，无法提供跨请求去重。

### 2.2 错误语义

| 状态码 | 场景 |
| --- | --- |
| 400 | 请求体或模型标识非法 |
| 401 | Token 缺失、过期、黑名单或账号失效 |
| 403 | 账号无可用角色 |
| 404 | 会话/运行不存在或不属于当前用户 |
| 409 | 会话仍在生成、同一请求正在处理、运行仍有执行者 |
| 503 | 模型未配置或数据库不可用 |

## 3. SSE 事件

| 事件 | 关键字段 |
| --- | --- |
| `start` | `user_message_id`、`message_id`、`run_id`、`replayed`、`operation`、`write_state`、`missing_fields` |
| `dispatch` | `step_order`、`agent_name`、`task`、`call_id` |
| `agent_start` / `agent_end` | `step_order`、`agent_name`、`status`、`duration_ms`、`summary`、`call_id` |
| `tool_start` | `step_order`、`agent_name`、`tool_name`、`args`、`call_id`、`parent_call_id` |
| `tool_end` | 同一 `step_order`，附 `summary`、`duration_ms`、`status` |
| `summarize` | `step_order`、`status`、`duration_ms`、`summary` |
| `token` | `{"content": "..."}` |
| `done` | `message_id`、`run_id`、`expert_count`、`tool_call_count`、`total_duration_ms`、`replayed`、`drafts[]` |
| `error` | `message` |

- `step_order` 在同一轮内自增，`tool_start` 与 `tool_end` 共用序号；`call_id` 区分同一专家的多次并发调用，`parent_call_id` 指向所属专家调用。
- 步骤、文本与心跳每秒增量落库；文本按实际变化比对写入，而不是累计到固定字数才写。提交成功后才推进"已写入"状态，失败时步骤回到待写队列，最终刷新会重试，因此正常运行时崩溃最多丢一个刷新周期。
- 页面刷新或断线后可用 `/ai/runs/{run_id}` 读取已有运行并续显；前端会在历史中发现 `running` 消息时轮询该接口，不重新执行建单。
- 客户端中断后不再调度新操作；已提交的草稿仍然存在，`done`/运行详情会给出真实单号。

### 3.1 草稿卡片

`done.drafts[]` 与消息 `drafts[]` 字段一致：

```json
{"order_id": 18, "order_no": "CK202611270001", "order_type": "outbound", "status": 0,
 "status_label": "草稿", "source": "AI_AGENT", "item_count": 1,
 "created_at": "...", "posted_at": null, "reject_reason": null}
```

卡片状态实时读取 `wms_inbound_order` / `wms_outbound_order`，人工审核后再次拉取历史即同步为「已生效」或「已驳回」。

## 4. 写操作状态机

服务端根据用户消息、会话上下文与数据库档案判定，不读取模型输出：

| 状态 | 条件 | 工具装配 |
| --- | --- | --- |
| `readonly` | 查询、统计、建议、询问流程、假设演示，或明确拒绝建单 | 只读工具 |
| `collecting` | 有显式建单表述，但仓库/商品/数量/批次/权限缺失或多义 | 只读工具 + 追问 |
| `ready` | 显式建单且上述条件齐全，数量与批次能按商品一一对应 | 只读工具 + 对应类型的建单工具 |

判定补充：

- "如何/怎么/流程/需要哪些/解释/如果/假如"等表述按只读处理；带"帮我/给我"的明确委托仍算请求，"可以吗"只是征询许可。
- 数量、批次按商品在消息中出现的顺序归位；一个商品对应多个数值、单位（如箱与件）与档案不一致时进入 `collecting`。
- 已确认的仓库与明细写入 `ai_session.context_state.items`，下一轮"还是这个仓库/按刚才的商品和数量"可续接，但仍需本轮出现建单指令才重新授权。

放行条件（全部满足才写库）：

1. 本轮 `operation` 与工具类型一致（`create_inbound_draft` / `create_outbound_draft`）；
2. 用户具备 `wms:inbound:add` 或 `wms:outbound:add`；
3. 工具参数与本轮从用户消息解析的明细逐项一致：仓库、商品集合、每个商品的数量合计、指定批次；
4. 首次写调用绑定参数摘要，同轮后续调用摘要必须一致，防止确认后改参数；
5. 运行记录仍为 `running` 且会话属于当前用户；
6. 主从表、序列表与草稿回执在同一事务内提交。

未通过时工具返回 `ok=false` 与 `blocked`（`write_not_ready`/`write_kind_mismatch`/`write_forbidden`/`write_args_mismatch`/`write_args_changed`），不写库。`write_args_mismatch` 会附 `mismatches[]`，列出期望值与实际值。

## 5. 幂等设计

| 层级 | 键 | 作用 |
| --- | --- | --- |
| 请求 | `ai_run(session_id, client_request_id)` 唯一 | 网络重试只产生一次执行 |
| 操作 | `operation_id`（由 `client_request_id` 派生，否则随机） | 同一次发送内稳定的业务操作标识 |
| 草稿 | `request_key = ai:{operation_id}:{kind}:{sha256(标准化参数)}`，`(created_by, request_key)` 唯一 | 同轮重复调用复用同一张单 |
| 回执 | `ai_draft_operation.operation_key` 唯一 | 断流后仍能找回单号与单据状态 |

用户明确发起第二次建单时，新的请求得到新的 `operation_id`，因此可以生成新单。

## 6. 权限矩阵

| 工具 | 权限 | 读写 |
| --- | --- | --- |
| `query_realtime_inventory` | `wms:inventory:list` | 读 |
| `query_inventory_logs` | `wms:inventory:logs` | 读 |
| `query_warehouse_structure` | `wms:warehouse:list` 或 `wms:inventory:list` | 读 |
| `query_orders` | `wms:{inbound,outbound,transfer,stocktake,adjust,freeze}:list` | 读 |
| `query_draft_references` | `wms:inbound:add` 或 `wms:outbound:add` | 读 |
| `create_inbound_draft` | `wms:inbound:add` | 草稿写 |
| `create_outbound_draft` | `wms:outbound:add` | 草稿写 |
| `query_low_stock_alerts` | `wms:alert:list` | 读 |
| `query_overstock_alerts` | `wms:alert:list` | 读 |
| `query_expiring_alerts` | `wms:alert:list` | 读 |
| `suggest_replenishment` | `wms:alert:list` | 读 |
| `get_inout_trend` | `wms:dashboard:view` | 读 |
| `get_inventory_structure` | `wms:dashboard:view` | 读 |
| `get_order_status_stats` | `wms:dashboard:view` | 读 |

工具在装配时按权限过滤，执行时再次校验，权限变更立即生效。

## 7. 查询口径

| 主题 | 口径 |
| --- | --- |
| 分页 | `limit`（≤50）+ `offset`；返回 `offset`、`truncated`、`next_offset`；`row_count` 是真实命中数，`rows` 可能只是样例 |
| 库存 | 在库量 `quantity`、冻结量 `frozen_quantity`、可用量 `available_quantity`，按仓库/库位/批次呈现 |
| 低库存 | 默认只统计已有库存记录的 商品×仓库；`include_missing=true` 时按「每个启用仓库 × 每个启用商品」评估，未建记录按 0 计并标记 `no_stock_record` |
| 临期 | `scope=expiring` 未来 N 天、`scope=expired` 已过期、`scope=all` 两者；`days_left` 负数表示已过期天数 |
| 补货 | `suggested = max(max_stock, min_stock) - 可用量 - 待入库量`，不为负；待入库含 `status` 0/1 的入库单，可用 `consider_pending_inbound=false` 关闭 |
| 单据状态 | 0 草稿、1 待审核、2 已生效、3 已驳回 |
| 汇总 | 工具记录超出预算时优先保留草稿回执、失败原因、缺口与截断信息，其余按 `row_count` 说明省略 |

## 8. 验收用例

演示数据：中央仓(1)、华东仓(2)；20W 充电器 `CHG-20W`(1) 批次 B001 位于库位 STO-A01(102)，在库 40、冻结 20、可用 20；65W 充电器(2) 在华东仓可用 15、安全下限 20；设备清洁湿巾(4) 批次 W001 于 2026-12-07 到期。

| 编号 | 请求 | 预期 | 覆盖测试 |
| --- | --- | --- | --- |
| A1 | 查询中央仓 20W 充电器库存 | 返回 B001 可用 20，注明口径与截断情况 | 真实模型联调 |
| A2 | 对比两仓 65W 库存并给补货建议 | 组合调用库存与预警专家，建议量不重复计入在途 | 真实模型联调 |
| A3 | 创建出库草稿：65W 充电器 5 件 | 追问仓库或批次，不建单 | `test_build_policy_collects_missing_fields` |
| A4 | 帮我在中央仓创建出库草稿：20W 充电器 5 件，批次 B001 | 落库 `CK...`，`status=0`、`source=AI_AGENT`，库存不变 | `test_outbound_draft_created_with_receipt` |
| A5 | 同上但数量 25 | 整单拒绝，返回缺口 5，不产生单据 | `test_outbound_shortage_rejects_whole_draft` |
| A6 | 无 `wms:outbound:add` 的账号发建单请求 | 策略进入 `collecting`，不装配建单工具 | `test_build_policy_without_permission` |
| A7 | 生成中客户端断开，重新打开会话 | 保留已生成文本与步骤，状态 `interrupted` | `test_stale_run_is_reclaimed` |
| A8 | 分析补货建议 | 只返回建议，不建单 | `test_analysis_request_does_not_authorize_write` |
| A9 | 同一 `client_request_id` 重试 | 只执行一次，回放同一 `message_id` 与单号 | `test_same_client_request_id_is_replayed_once` |
| A10 | 明确要求再建一单 | 新运行、新 `operation_id`、新单号 | `test_new_operation_creates_new_draft` |
| A11 | 人工审核 AI 草稿 | 库存与流水按 Java 事务变化，卡片状态同步（依赖 Java 业务层） | 跨服务用例（待 Java 实现） |
| A12 | 进程重启后查看旧运行 | `running` 超时收敛为 `interrupted`，不再永久显示执行中 | `test_run_cancel_requires_stale_lease` |
| A13 | 如何在中央仓创建出库草稿，20W 充电器 5 件？ | 只解释流程，不装配建单工具 | `test_inquiry_never_authorizes_write` |
| A14 | 用户要求 5 件，模型调用传入 6 件 | 拒绝保存，返回 `write_args_mismatch` 与期望数量 | `test_write_guard_rejects_model_changed_quantity` |
| A15 | 还是这个仓库，按刚才的商品和数量再建一张出库草稿 | 沿用已确认明细并重新授权，数量仍为 5 | `test_confirmed_items_are_inherited_with_context_reference` |

## 9. 测试

```bash
cd ~/Dev/WMS/fastapi
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/python -m pytest -q
```

- 工具与数据库集成：`tests/test_drafts.py`、`tests/test_tools.py`，单连接事务执行后整体回滚。
- 策略、记忆与汇总：`tests/test_policy.py`、`tests/test_memory.py`、`tests/test_evidence.py`。
- 运行日志：`tests/test_journal.py`，模拟提交失败，验证文本逐次落库、步骤重回队列与最终刷新可重试。
- 接口与 SSE：`tests/test_chat_api.py`，用可控模型响应替换真实模型，覆盖幂等、重放、运行查询、取消与越权读取。
- 跨服务闭环（Java Token → AI 建草稿 → Vue 核验 → Java 审核 → 库存核对）需要 Spring Boot 业务层就绪后补测。
