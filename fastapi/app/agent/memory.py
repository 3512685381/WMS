"""会话记忆：更早对话的摘要、最近完整轮次，以及已确认的业务上下文。

摘要与最近消息分工明确：
- 摘要只用于理解上下文，不携带任何新的建单授权。
- 最近消息按完整轮次保留，超出字符预算时从最旧的一侧截断，并保证以用户消息开头。
- 业务上下文只记录用户或工具已经确认过的仓库、商品、批次与草稿结果。
"""

import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from fastapi.concurrency import run_in_threadpool
from sqlalchemy import select, text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.agent.llm import ChatModel, ModelError
from app.agent.policy import TurnPolicy, pending_is_fresh
from app.agent.tools.common import ToolArguments
from app.core.database import SessionLocal
from app.models.memory import AiMessage, AiSession

logger = logging.getLogger(__name__)
CONTEXT_CHAR_LIMIT = 16000
RECENT_MESSAGE_LIMIT = 40
SUMMARY_KEEP_TURNS = 6
SUMMARY_TRIGGER_CHARS = 4000
SUMMARY_TRIGGER_TURNS = 6
SUMMARY_CHAR_LIMIT = 3000
SUMMARY_MESSAGE_CHARS = 1200
SUMMARY_PROMPT = (
    "把更早的仓储助手对话压缩为不超过 800 字的中文摘要。保留：已确认的仓库、商品、批次和数量，"
    "用户明确提出的建单要求及其单号与结果，尚未完成的追问，以及明确的否定和约束。"
    "不要编造单号、数量或执行状态，不要给出新的建单授权。只输出摘要正文。"
)


@dataclass(frozen=True, slots=True)
class Conversation:
    summary: str | None
    messages: list[dict[str, str]]
    context: dict[str, Any]
    context_text: str


def load_conversation(db: Session, session: AiSession) -> Conversation:
    cutoff = session.context_summary_through_message_id or 0
    rows = db.execute(
        select(AiMessage.role, AiMessage.content)
        .where(
            AiMessage.session_id == session.id,
            AiMessage.is_deleted == 0,
            AiMessage.content != "",
            AiMessage.id > cutoff,
        )
        .order_by(AiMessage.id.desc())
        .limit(RECENT_MESSAGE_LIMIT)
        .with_for_update(read=True)
    ).all()

    remaining = CONTEXT_CHAR_LIMIT
    messages: list[dict[str, str]] = []
    for row in rows:
        if remaining <= 0:
            break
        content = row.content[-remaining:] if len(row.content) > remaining else row.content
        messages.append({"role": row.role, "content": content})
        remaining -= len(content)
    messages.reverse()
    while messages and messages[0]["role"] != "user":
        messages.pop(0)

    context = dict(session.context_state or {})
    return Conversation(session.context_summary, messages, context, render_context(context))


def render_context(state: dict[str, Any]) -> str:
    if not state:
        return "（暂无已确认的业务条件）"
    lines: list[str] = []
    warehouse = state.get("warehouse")
    if warehouse:
        lines.append(f"已确认仓库：{warehouse.get('name')}（id={warehouse.get('id')}）")
    products = state.get("products") or []
    if products:
        lines.append("最近涉及商品：" + "、".join(
            f"{item.get('name')}（{item.get('sku_code')}，id={item.get('id')}）" for item in products[:6]
        ))
    items = state.get("items") or []
    if items:
        parts = []
        for item in items[:6]:
            detail = f"{item.get('name') or item.get('sku_code')} {item.get('quantity')}{item.get('unit') or ''}"
            if item.get("batch_no"):
                detail += f"（批次 {item['batch_no']}）"
            parts.append(detail)
        lines.append("已确认明细：" + "、".join(parts))
    batches = state.get("batches") or []
    if batches:
        lines.append("最近涉及批次：" + "、".join(str(batch) for batch in batches[:6]))
    draft = state.get("draft")
    if draft:
        lines.append(
            f"最近一张 AI 草稿：{draft.get('order_no')}（{draft.get('kind')}，"
            f"order_id={draft.get('order_id')}）。审核结果以单据查询为准。"
        )
    pending = state.get("pending_operation")
    if pending and pending_is_fresh(pending):
        lines.append(
            f"未完成的建单请求：{pending.get('kind')}，缺少 "
            + "、".join(pending.get("missing_fields") or [] or ["无"])
        )
    lines.append("库存数量、预警和单据状态必须重新查询；历史上下文不能作为建单授权。")
    return "\n".join(lines)


def sync_pending_operation(db: Session, session: AiSession, policy: TurnPolicy) -> None:
    """把未完成的建单请求写入会话上下文，便于下一轮补充字段后继续。"""
    state = dict(session.context_state or {})
    pending = state.get("pending_operation")
    if policy.draft_kind is not None and policy.state == "collecting":
        state["pending_operation"] = {
            "kind": policy.draft_kind,
            "missing_fields": list(policy.missing_fields),
            "message": policy.resolution_text or policy.user_message,
            "created_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        }
    elif pending is not None and (policy.state == "ready" or policy.operation.value in ("query", "analysis")):
        state.pop("pending_operation", None)
    elif pending is not None and not pending_is_fresh(pending):
        state.pop("pending_operation", None)
    session.context_state = state


def remember_tool_context(
    db: Session, session_id: str, args: ToolArguments, outcome: dict | None,
) -> None:
    """把工具执行中已经核实的仓库、商品、批次与草稿结果写回会话上下文。"""
    try:
        patch = _context_patch(db, args, outcome)
        if not patch:
            return
        session = db.get(AiSession, session_id, with_for_update=True)
        if session is None or session.is_deleted:
            return
        state = dict(session.context_state or {})
        for key, value in patch.items():
            if value:
                state[key] = value
        session.context_state = state
    except SQLAlchemyError:
        logger.warning("更新业务上下文失败，已忽略")


def _context_patch(db: Session, args: ToolArguments, outcome: dict | None) -> dict[str, Any]:
    data = args.model_dump(mode="json")
    outcome = outcome or {}
    rows = outcome.get("rows") or []

    warehouse: dict | None = None
    warehouse_id = data.get("warehouse_id")
    if not warehouse_id:
        warehouse_id = next((row.get("warehouse_id") for row in rows[:5] if row.get("warehouse_id")), None)
    if warehouse_id:
        row = db.execute(
            text("SELECT id, name FROM wms_warehouse WHERE id = :id AND is_deleted = 0"), {"id": warehouse_id},
        ).mappings().first()
        if row:
            warehouse = {"id": row["id"], "name": row["name"]}

    candidate_ids: list[int] = []
    if data.get("product_id"):
        candidate_ids.append(int(data["product_id"]))
    for item in data.get("items") or []:
        if item.get("product_id"):
            candidate_ids.append(int(item["product_id"]))
    for row in rows[:5]:
        if row.get("product_id"):
            candidate_ids.append(int(row["product_id"]))

    products: list[dict] = []
    meta: dict[int, dict] = {}
    for product_id in dict.fromkeys(candidate_ids):
        row = db.execute(
            text("""
                SELECT id, name, sku_code, unit, batch_managed FROM wms_product
                WHERE id = :id AND is_deleted = 0
                """),
            {"id": product_id},
        ).mappings().first()
        if not row:
            continue
        meta[product_id] = dict(row)
        products.append({
            "id": row["id"], "name": row["name"], "sku_code": row["sku_code"],
            "unit": row["unit"], "batch_managed": bool(row["batch_managed"]),
        })

    batches: list[str] = []
    items: list[dict] = []
    for item in data.get("items") or []:
        product_id = int(item["product_id"]) if item.get("product_id") else None
        batch_no = item.get("batch_no")
        if batch_no and str(batch_no).upper() != "NO_BATCH":
            batches.append(str(batch_no))
        product = meta.get(product_id) if product_id else None
        if not product or item.get("quantity") in (None, ""):
            continue
        items.append({
            "product_id": product_id,
            "name": product["name"],
            "sku_code": product["sku_code"],
            "unit": product["unit"],
            "quantity": str(item["quantity"]),
            "batch_no": str(batch_no) if batch_no and str(batch_no).upper() != "NO_BATCH" else None,
        })
    for row in rows[:5]:
        if row.get("batch_no") and row["batch_no"] != "NO_BATCH":
            batches.append(row["batch_no"])

    patch: dict[str, Any] = {}
    if warehouse:
        patch["warehouse"] = warehouse
    if products:
        patch["products"] = products[:10]
    if items:
        # 保留用户已确认的明细，下一轮"按刚才的商品和数量"可以直接沿用。
        patch["items"] = items[:10]
    if batches:
        patch["batches"] = list(dict.fromkeys(batches))[:6]
    if outcome.get("order_no"):
        patch["draft"] = {
            "order_no": outcome["order_no"],
            "order_id": outcome.get("order_id"),
            "kind": outcome.get("order_type") or data.get("kind"),
            "status": outcome.get("status"),
        }
    return patch


def _summary_plan(session_id: str) -> tuple[int, list[dict[str, str]], str | None] | None:
    with SessionLocal() as db:
        session = db.get(AiSession, session_id)
        if session is None or session.is_deleted:
            return None
        cutoff = session.context_summary_through_message_id or 0
        rows = db.execute(
            select(AiMessage.id, AiMessage.role, AiMessage.content)
            .where(
                AiMessage.session_id == session_id,
                AiMessage.is_deleted == 0,
                AiMessage.content != "",
                AiMessage.id > cutoff,
            )
            .order_by(AiMessage.id)
        ).all()
        starts = [index for index, row in enumerate(rows) if row.role == "user"]
        if len(starts) <= SUMMARY_KEEP_TURNS:
            return None
        older = rows[:starts[-SUMMARY_KEEP_TURNS]]
        if not older:
            return None
        total = sum(len(row.content) for row in older)
        if total < SUMMARY_TRIGGER_CHARS and len(starts) - SUMMARY_KEEP_TURNS < SUMMARY_TRIGGER_TURNS:
            return None
        transcript = [
            {
                "role": row.role,
                "content": row.content[:SUMMARY_MESSAGE_CHARS],
            }
            for row in older
        ]
        return older[-1].id, transcript, session.context_summary


def _save_summary(session_id: str, through_message_id: int, summary: str) -> None:
    with SessionLocal.begin() as db:
        session = db.get(AiSession, session_id, with_for_update=True)
        if session is None or session.is_deleted:
            return
        session.context_summary = summary[:SUMMARY_CHAR_LIMIT]
        session.context_summary_through_message_id = through_message_id


async def refresh_summary(model: ChatModel, session_id: str) -> bool:
    """把较早的对话压缩进摘要。失败只记录日志，不影响本轮回复。"""
    try:
        plan = await run_in_threadpool(_summary_plan, session_id)
        if plan is None:
            return False
        through_message_id, transcript, previous = plan
        lines = [f"{'用户' if item['role'] == 'user' else '助手'}：{item['content']}" for item in transcript]
        if previous:
            lines.insert(0, f"已有摘要：{previous}")
        summary = await model.complete(
            [{"role": "user", "content": "\n".join(lines)}], system_prompt=SUMMARY_PROMPT, max_tokens=600,
        )
        if not summary.strip():
            return False
        await run_in_threadpool(_save_summary, session_id, through_message_id, summary.strip())
        return True
    except (ModelError, SQLAlchemyError) as error:
        logger.info("本轮跳过摘要更新（%s）", type(error).__name__)
        return False
