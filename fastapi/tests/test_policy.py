from uuid import uuid4

import pytest

from app.agent.policy import Intent, Operation, TurnPolicy, build_policy, classify, draft_digest
from app.agent.tools.common import ToolError
from app.agent.tools.drafts import OutboundDraft


@pytest.mark.parametrize("message,kind", [
    ("帮我在中央仓建一张入库草稿，供应商华源电子，20W 充电器 B001 100 件", "inbound"),
    ("创建出库单：中央仓 65W 充电器 5 件", "outbound"),
    ("生成一张出库草稿", "outbound"),
    ("按这个补货建议建入库单", "inbound"),
    ("新建入库单", "inbound"),
    ("开一张出库单", "outbound"),
])
def test_explicit_write_intent(message, kind):
    intent = classify(message)
    assert intent.kind == kind
    assert intent.explicit is True
    assert intent.refused is False


@pytest.mark.parametrize("message", [
    "分析一下补货建议",
    "查询中央仓库存",
    "看看入库单列表",
    "提交这张出库单",
    "统计入库单状态分布",
    "建议生成补货计划",
    "对比两个仓库的出库单数量",
])
def test_readonly_messages_never_authorize_write(message):
    intent = classify(message)
    assert intent.kind is None
    assert intent.operation() in (Operation.QUERY, Operation.ANALYSIS)


@pytest.mark.parametrize("message", [
    "不要建单，只给建议",
    "先不要生成草稿，我问一下库存",
    "只需要建议，不需要创建出库单",
    "别建单",
])
def test_refusal_wins_over_write_wording(message):
    intent = classify(message)
    assert intent.kind is None
    assert intent.refused is True


def test_ambiguous_type_does_not_authorize_write():
    intent = classify("帮我创建一张入库或出库单")
    assert intent.explicit is True
    assert intent.kind is None


def outbound_args(quantity="5"):
    return OutboundDraft(
        warehouse_id=1, customer_id=1,
        items=[{"product_id": 1, "location_id": 102, "quantity": quantity, "batch_no": "B001"}],
    )


def ready_policy() -> TurnPolicy:
    return TurnPolicy(
        operation=Operation.CREATE_OUTBOUND_DRAFT,
        user_message="创建出库草稿",
        operation_id=str(uuid4()),
        state="ready",
        confirmed={"warehouse": {"id": 1, "name": "中央仓", "source": "message"}},
    )


def test_write_guard_binds_first_digest(admin):
    policy = ready_policy()
    args = outbound_args()
    digest = policy.authorize_write(admin, "create_outbound_draft", args)
    assert digest == draft_digest("outbound", args)
    assert policy.bound_digest == digest
    # 同一轮内重复调用且参数一致时放行，交给幂等键复用同一张单
    policy.authorize_write(admin, "create_outbound_draft", args)
    # 参数被改动后必须重新确认
    with pytest.raises(ToolError) as error:
        policy.authorize_write(admin, "create_outbound_draft", outbound_args("6"))
    assert error.value.details["blocked"] == "write_args_changed"


def test_write_guard_blocks_when_not_ready(admin):
    policy = TurnPolicy(
        operation=Operation.CREATE_OUTBOUND_DRAFT,
        user_message="创建出库草稿",
        operation_id=str(uuid4()),
        state="collecting",
        missing_fields=("批次",),
    )
    with pytest.raises(ToolError) as error:
        policy.authorize_write(admin, "create_outbound_draft", outbound_args())
    assert error.value.details["blocked"] == "write_not_ready"
    assert error.value.details["missing_fields"] == ["批次"]


def test_write_guard_rejects_other_tool_and_permission():
    policy = ready_policy()
    with pytest.raises(ToolError) as error:
        policy.authorize_write(
            type("User", (), {"permissions": frozenset({"wms:outbound:add"})})(),
            "create_inbound_draft", outbound_args(),
        )
    assert error.value.details["blocked"] == "write_kind_mismatch"

    with pytest.raises(ToolError) as error:
        policy.authorize_write(type("User", (), {"permissions": frozenset()})(), "create_outbound_draft",
                               outbound_args())
    assert error.value.details["blocked"] == "write_forbidden"


def test_digest_is_order_insensitive():
    first = OutboundDraft(warehouse_id=1, items=[
        {"product_id": 1, "location_id": 102, "quantity": "5", "batch_no": "B001"},
        {"product_id": 2, "location_id": 102, "quantity": "3", "batch_no": "B002"},
    ])
    second = OutboundDraft(warehouse_id=1, items=[
        {"product_id": 2, "location_id": 102, "quantity": "3", "batch_no": "B002"},
        {"product_id": 1, "location_id": 102, "quantity": "5.000", "batch_no": "B001"},
    ])
    assert draft_digest("outbound", first) == draft_digest("outbound", second)


def test_build_policy_resolves_entities(db, admin):
    policy = build_policy(
        db, admin, "帮我在中央仓建一张出库草稿，20W 充电器 5 件，批次 B001", operation_id=str(uuid4()),
    )
    assert policy.operation is Operation.CREATE_OUTBOUND_DRAFT
    assert policy.state == "ready"
    assert policy.missing_fields == ()
    assert policy.confirmed["warehouse"]["id"] == 1
    assert policy.confirmed["products"][0]["sku_code"] == "CHG-20W"
    assert policy.confirmed["batch"] == "B001"


def test_build_policy_collects_missing_fields(db, admin):
    policy = build_policy(db, admin, "创建出库草稿：65W 充电器 5 件", operation_id=str(uuid4()))
    assert policy.state == "collecting"
    assert "仓库" in policy.missing_fields
    assert "批次" in policy.missing_fields
    assert policy.writes_allowed is False


def test_build_policy_without_permission(db, limited_user):
    policy = build_policy(
        db, limited_user, "帮我在中央仓建一张出库草稿，20W 充电器 5 件，批次 B001",
        operation_id=str(uuid4()),
    )
    assert policy.state == "collecting"
    assert "当前账号的单据新增权限" in policy.missing_fields


def test_pending_operation_follow_up(db, admin):
    first = build_policy(db, admin, "创建出库草稿：20W 充电器 5 件", operation_id=str(uuid4()))
    assert first.state == "collecting"
    context = {"pending_operation": {
        "kind": "outbound",
        "missing_fields": ["仓库", "批次"],
        "message": "创建出库草稿：20W 充电器 5 件",
        "created_at": __import__("datetime").datetime.now(
            __import__("datetime").timezone.utc,
        ).isoformat(timespec="seconds"),
    }}
    follow_up = build_policy(
        db, admin, "中央仓，批次 B001", operation_id=str(uuid4()), context=context,
    )
    assert follow_up.operation is Operation.CREATE_OUTBOUND_DRAFT
    assert follow_up.state == "ready"
    assert follow_up.confirmed["batch"] == "B001"


def test_pending_operation_ignores_query_turn(db, admin):
    context = {"pending_operation": {
        "kind": "outbound",
        "missing_fields": ["仓库"],
        "message": "创建出库草稿：20W 充电器 5 件",
        "created_at": __import__("datetime").datetime.now(
            __import__("datetime").timezone.utc,
        ).isoformat(timespec="seconds"),
    }}
    policy = build_policy(db, admin, "查一下中央仓的库存", operation_id=str(uuid4()), context=context)
    assert policy.state == "readonly"
    assert policy.writes_allowed is False
    assert isinstance(policy.operation, Operation)


def test_intent_defaults():
    assert Intent().operation() is Operation.QUERY
    assert Intent(analysis=True).operation() is Operation.ANALYSIS
