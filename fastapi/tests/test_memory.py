from uuid import uuid4

from app.agent.memory import CONTEXT_CHAR_LIMIT, load_conversation, render_context, sync_pending_operation
from app.agent.memory import remember_tool_context
from app.agent.policy import Operation, TurnPolicy
from app.agent.tools.drafts import OutboundDraft
from app.models.memory import AiMessage, AiSession


def make_session(db, messages):
    session = AiSession(id=str(uuid4()), user_id=1, created_by=1, title="记忆测试")
    db.add(session)
    db.flush()
    for role, content in messages:
        db.add(AiMessage(session_id=session.id, role=role, content=content, created_by=1))
    db.flush()
    return session


def test_conversation_starts_with_user_message(db):
    session = make_session(db, [
        ("assistant", "上一轮的回复"),
        ("user", "第一问"),
        ("assistant", "第一答"),
        ("user", "第二问"),
    ])
    conversation = load_conversation(db, session)
    assert conversation.messages[0]["role"] == "user"
    assert len(conversation.messages) == 3


def test_conversation_respects_summary_cutoff(db):
    session = make_session(db, [("user", "旧问题"), ("assistant", "旧回答"), ("user", "新问题")])
    old = db.query(AiMessage).filter(AiMessage.session_id == session.id).order_by(AiMessage.id).all()
    session.context_summary = "更早的摘要"
    session.context_summary_through_message_id = old[1].id
    session.context_state = {"warehouse": {"id": 1, "name": "中央仓"}}
    db.flush()

    conversation = load_conversation(db, session)
    assert conversation.summary == "更早的摘要"
    assert [item["content"] for item in conversation.messages] == ["新问题"]
    assert "中央仓" in conversation.context_text


def test_conversation_truncates_to_char_budget(db):
    big = "库存明细" * 4000
    session = make_session(db, [
        ("user", big), ("assistant", big), ("user", "最近问题"),
    ])
    conversation = load_conversation(db, session)
    total = sum(len(item["content"]) for item in conversation.messages)
    assert total <= CONTEXT_CHAR_LIMIT
    assert conversation.messages[0]["role"] == "user"
    assert conversation.messages[-1]["content"] == "最近问题"


def test_render_context_includes_confirmed_state():
    text = render_context({
        "warehouse": {"id": 1, "name": "中央仓"},
        "products": [{"id": 1, "name": "20W 充电器", "sku_code": "CHG-20W"}],
        "batches": ["B001"],
        "draft": {"order_no": "CK202611250001", "order_id": 2, "kind": "outbound"},
    })
    assert "中央仓" in text
    assert "CHG-20W" in text
    assert "CK202611250001" in text
    assert "必须重新查询" in text


def test_sync_pending_operation_sets_and_clears(db):
    session = make_session(db, [])
    collecting = TurnPolicy(
        operation=Operation.CREATE_OUTBOUND_DRAFT, user_message="创建出库草稿",
        operation_id=str(uuid4()), state="collecting", missing_fields=("仓库", "批次"),
    )
    sync_pending_operation(db, session, collecting)
    pending = session.context_state["pending_operation"]
    assert pending["kind"] == "outbound"
    assert pending["missing_fields"] == ["仓库", "批次"]

    ready = TurnPolicy(
        operation=Operation.CREATE_OUTBOUND_DRAFT, user_message="创建出库草稿",
        operation_id=str(uuid4()), state="ready",
    )
    sync_pending_operation(db, session, ready)
    assert "pending_operation" not in session.context_state


def test_remember_tool_context_records_draft(db):
    session = make_session(db, [])
    args = OutboundDraft(warehouse_id=1, items=[
        {"product_id": 1, "location_id": 102, "quantity": "5", "batch_no": "B001"},
    ])
    remember_tool_context(db, session.id, args, {
        "order_no": "CK202611270001", "order_id": 99, "order_type": "outbound", "status": 0,
    })
    state = session.context_state
    assert state["warehouse"]["id"] == 1
    assert state["products"][0]["sku_code"] == "CHG-20W"
    assert state["batches"] == ["B001"]
    assert state["draft"]["order_no"] == "CK202611270001"
    assert state["draft"]["kind"] == "outbound"


def test_remember_tool_context_records_items(db):
    session = make_session(db, [])
    args = OutboundDraft(warehouse_id=1, items=[
        {"product_id": 1, "location_id": 102, "quantity": "5", "batch_no": "B001"},
    ])
    remember_tool_context(db, session.id, args, {
        "order_no": "CK202611280001", "order_id": 100, "order_type": "outbound", "status": 0,
    })
    item = session.context_state["items"][0]
    assert item["product_id"] == 1
    assert item["name"] == "20W 充电器"
    assert item["quantity"] == "5"
    assert item["unit"] == "件"
    assert item["batch_no"] == "B001"


def test_render_context_includes_confirmed_items():
    text = render_context({
        "warehouse": {"id": 1, "name": "中央仓"},
        "items": [{
            "product_id": 1, "name": "20W 充电器", "sku_code": "CHG-20W",
            "unit": "件", "quantity": "5", "batch_no": "B001",
        }],
    })
    assert "已确认明细" in text
    assert "20W 充电器 5件" in text
    assert "批次 B001" in text
