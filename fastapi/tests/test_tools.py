from decimal import Decimal

from app.agent.tools import alert_tools, inventory_tools, order_tools
from app.agent.tools.common import ToolContext


def context(db, admin):
    return ToolContext(db, admin, "11111111-1111-1111-1111-111111111111", 1, "op-tools")


def test_inventory_paging_uses_offset(db, admin):
    ctx = context(db, admin)
    first = inventory_tools.query_realtime_inventory(ctx, inventory_tools.InventoryQuery(limit=2))
    second = inventory_tools.query_realtime_inventory(ctx, inventory_tools.InventoryQuery(limit=2, offset=2))
    assert first["next_offset"] == 2
    assert first["truncated"] is True
    keys = lambda page: {(row["location_id"], row["product_id"], row["batch_no"]) for row in page["rows"]}
    assert keys(first).isdisjoint(keys(second))
    assert second["offset"] == 2


def test_low_stock_missing_records_are_opt_in(db, admin):
    ctx = context(db, admin)
    default = alert_tools.query_low_stock_alerts(ctx, alert_tools.LowStockArgs(limit=50))
    widened = alert_tools.query_low_stock_alerts(
        ctx, alert_tools.LowStockArgs(limit=50, include_missing=True),
    )
    assert len(widened["rows"]) > len(default["rows"])
    assert any(row["no_stock_record"] == 1 for row in widened["rows"])


def test_expiring_scope_separates_expired(db, admin):
    ctx = context(db, admin)
    expiring = alert_tools.query_expiring_alerts(ctx, alert_tools.ExpiringArgs(days=30))
    expired = alert_tools.query_expiring_alerts(ctx, alert_tools.ExpiringArgs(scope="expired"))
    both = alert_tools.query_expiring_alerts(ctx, alert_tools.ExpiringArgs(days=30, scope="all"))

    assert all(row["expiry_state"] == "expiring" for row in expiring["rows"])
    assert all(row["expiry_state"] == "expired" for row in expired["rows"])
    assert len(both["rows"]) == len(expiring["rows"]) + len(expired["rows"])


def test_replenishment_considers_pending_inbound(db, admin):
    ctx = context(db, admin)
    with_pending = alert_tools.suggest_replenishment(ctx, alert_tools.ReplenishArgs(limit=50))
    without_pending = alert_tools.suggest_replenishment(
        ctx, alert_tools.ReplenishArgs(limit=50, consider_pending_inbound=False),
    )

    pending_products = {(row["warehouse_id"], row["product_id"]) for row in with_pending["rows"]}
    all_products = {(row["warehouse_id"], row["product_id"]) for row in without_pending["rows"]}
    assert pending_products < all_products, "待入库量应把已有在途补货的商品移出建议列表"
    assert all(row["pending_inbound_quantity"] >= 0 for row in with_pending["rows"])


def test_replenishment_suggestion_never_negative(db, admin):
    ctx = context(db, admin)
    result = alert_tools.suggest_replenishment(
        ctx, alert_tools.ReplenishArgs(limit=50, consider_pending_inbound=False),
    )
    for row in result["rows"]:
        assert row["suggested_quantity"] >= 0
        assert row["shortage"] > 0


def test_order_query_paging(db, admin):
    ctx = context(db, admin)
    first = order_tools.query_orders(ctx, order_tools.OrderQuery(limit=2))
    second = order_tools.query_orders(ctx, order_tools.OrderQuery(limit=2, offset=2))
    assert len(first["rows"]) == 2
    assert first["next_offset"] == 2
    assert {(row["order_type"], row["order_id"]) for row in first["rows"]}.isdisjoint(
        {(row["order_type"], row["order_id"]) for row in second["rows"]}
    )


def test_order_detail_includes_items(db, admin):
    ctx = context(db, admin)
    result = order_tools.query_orders(ctx, order_tools.OrderQuery(order_no="RK202611250001"))
    order = result["rows"][0]
    assert order["source"] == "AI_AGENT"
    assert order["items"][0]["sku_code"] == "CHG-20W"
    assert Decimal(order["items"][0]["quantity"]) == Decimal("30.000")
