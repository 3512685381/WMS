from decimal import Decimal

import pytest
from sqlalchemy import text

from app.agent.tools.common import ToolContext, ToolError
from app.agent.tools.drafts import InboundDraft, OutboundDraft, create_inbound_draft, create_outbound_draft


def context(db, admin, turn, operation_id=None):
    session, run = turn
    return ToolContext(
        db, admin, session.id, run.id, operation_id or run.operation_id,
    )


def outbound(quantity="5", location_id=102, batch_no="B001", product_id=1):
    return OutboundDraft(
        warehouse_id=1, customer_id=1,
        items=[{"product_id": product_id, "location_id": location_id, "quantity": quantity, "batch_no": batch_no}],
    )


def order_count(db, kind):
    return db.scalar(text(f"SELECT COUNT(*) FROM wms_{kind}_order"))


def test_outbound_draft_created_with_receipt(db, admin, turn):
    ctx = context(db, admin, turn)
    before = order_count(db, "outbound")
    result = create_outbound_draft(ctx, outbound())

    assert result["order_no"].startswith("CK")
    assert len(result["order_no"]) == 14
    assert result["status"] == 0
    assert result["source"] == "AI_AGENT"
    assert result["order_type"] == "outbound"
    assert result["reused"] is False
    assert order_count(db, "outbound") == before + 1

    header = db.execute(text(
        "SELECT status, source, request_key FROM wms_outbound_order WHERE id = :id"
    ), {"id": result["order_id"]}).mappings().one()
    assert header["status"] == 0
    assert header["source"] == "AI_AGENT"
    assert header["request_key"].startswith(f"ai:{ctx.operation_id}:outbound:")

    item = db.execute(text(
        "SELECT quantity, batch_no FROM wms_outbound_item WHERE order_id = :id"
    ), {"id": result["order_id"]}).mappings().one()
    assert item["quantity"] == Decimal("5")
    assert item["batch_no"] == "B001"

    receipt = db.execute(text(
        "SELECT run_id, order_type, order_no, item_count FROM ai_draft_operation WHERE order_id = :id"
    ), {"id": result["order_id"]}).mappings().one()
    assert receipt["run_id"] == turn[1].id
    assert receipt["order_type"] == "outbound"
    assert receipt["order_no"] == result["order_no"]
    assert receipt["item_count"] == 1


def test_outbound_shortage_rejects_whole_draft(db, admin, turn):
    ctx = context(db, admin, turn)
    before = order_count(db, "outbound")
    with pytest.raises(ToolError) as error:
        create_outbound_draft(ctx, outbound(quantity="25"))
    assert error.value.details["shortages"][0]["shortage"] == Decimal("5")
    assert order_count(db, "outbound") == before


def test_outbound_aggregates_same_dimension(db, admin, turn):
    ctx = context(db, admin, turn)
    draft = OutboundDraft(warehouse_id=1, items=[
        {"product_id": 1, "location_id": 102, "quantity": "12", "batch_no": "B001"},
        {"product_id": 1, "location_id": 102, "quantity": "12", "batch_no": "B001"},
    ])
    with pytest.raises(ToolError) as error:
        create_outbound_draft(ctx, draft)
    shortage = error.value.details["shortages"][0]
    assert shortage["requested_quantity"] == Decimal("24")
    assert shortage["shortage"] == Decimal("4")


def test_draft_is_idempotent_within_operation(db, admin, turn):
    ctx = context(db, admin, turn)
    first = create_outbound_draft(ctx, outbound())
    before = order_count(db, "outbound")
    second = create_outbound_draft(ctx, outbound())

    assert second["reused"] is True
    assert second["order_no"] == first["order_no"]
    assert second["order_id"] == first["order_id"]
    assert order_count(db, "outbound") == before
    item_count = db.scalar(text(
        "SELECT item_count FROM ai_draft_operation WHERE order_id = :id"
    ), {"id": first["order_id"]})
    assert item_count == 1


def test_new_operation_creates_new_draft(db, admin, turn):
    first = create_outbound_draft(context(db, admin, turn), outbound())
    second = create_outbound_draft(context(db, admin, turn, operation_id="8f0f1e6a-18f7-4d1e-9b1f-1a2b3c4d5e6f"),
                                   outbound())
    assert second["reused"] is False
    assert second["order_no"] != first["order_no"]
    assert int(second["order_no"][-4:]) == int(first["order_no"][-4:]) + 1


def test_inbound_draft_without_batch_management(db, admin, turn):
    ctx = context(db, admin, turn)
    draft = InboundDraft(warehouse_id=1, supplier_id=1, items=[
        {"product_id": 3, "location_id": 105, "quantity": "10", "unit_price": "2.5"},
    ])
    result = create_inbound_draft(ctx, draft)
    assert result["order_type"] == "inbound"
    assert result["order_no"].startswith("RK")
    row = db.execute(text(
        "SELECT batch_no, production_date, expire_date FROM wms_inbound_item WHERE order_id = :id"
    ), {"id": result["order_id"]}).mappings().one()
    assert row["batch_no"] == "NO_BATCH"


def test_inbound_requires_batch_for_managed_product(db, admin, turn):
    ctx = context(db, admin, turn)
    draft = InboundDraft(warehouse_id=1, supplier_id=1, items=[
        {"product_id": 1, "location_id": 102, "quantity": "10"},
    ])
    with pytest.raises(ToolError) as error:
        create_inbound_draft(ctx, draft)
    assert "批次" in str(error.value)


def test_location_must_belong_to_warehouse(db, admin, turn):
    ctx = context(db, admin, turn)
    with pytest.raises(ToolError) as error:
        create_outbound_draft(ctx, outbound(location_id=201))
    assert "库位" in str(error.value)


def test_batch_must_exist_for_outbound(db, admin, turn):
    ctx = context(db, admin, turn)
    with pytest.raises(ToolError) as error:
        create_outbound_draft(ctx, outbound(batch_no="Z999"))
    shortage = error.value.details["shortages"][0]
    assert shortage["available_quantity"] == Decimal("0")
