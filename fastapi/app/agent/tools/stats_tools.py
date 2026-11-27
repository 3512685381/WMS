from datetime import datetime, timedelta
from decimal import Decimal
from typing import Literal

from app.agent.tools.common import DateArgs, ToolContext, ToolSpec, WarehouseArgs, match, paged, query_rows
from app.agent.tools.order_tools import OrderType, order_filter, order_warehouse_join, permitted_order_types
from app.schemas import SHANGHAI


class TrendArguments(WarehouseArgs):
    days: Literal[7, 30] = 7


class OrderStatsArguments(WarehouseArgs, DateArgs):
    order_type: OrderType | None = None


def get_inout_trend(ctx: ToolContext, args: TrendArguments) -> dict:
    today = datetime.now(SHANGHAI).date()
    first = today - timedelta(days=args.days - 1)
    last = today + timedelta(days=1)
    rows = query_rows(ctx.db, """
        SELECT DATE(g.created_at) AS day,
               SUM(CASE WHEN g.biz_type = 'INBOUND' THEN g.change_qty ELSE 0 END) AS inbound_quantity,
               SUM(CASE WHEN g.biz_type = 'OUTBOUND' THEN -g.change_qty ELSE 0 END) AS outbound_quantity
        FROM wms_inventory_log g
        JOIN wms_warehouse w ON w.id = g.warehouse_id AND w.is_deleted = 0
        WHERE g.is_deleted = 0 AND g.biz_type IN ('INBOUND', 'OUTBOUND')
          AND g.created_at >= :first AND g.created_at < :last
          AND (:warehouse IS NULL OR w.name LIKE :warehouse ESCAPE '!' OR w.code LIKE :warehouse ESCAPE '!')
        GROUP BY DATE(g.created_at)
        """, {"first": first, "last": last, "warehouse": match(args.warehouse)})
    by_day = {row["day"]: row for row in rows}
    return {"days": args.days, "rows": [
        by_day.get(day, {"day": day, "inbound_quantity": Decimal(0), "outbound_quantity": Decimal(0)})
        for day in (first + timedelta(days=offset) for offset in range(args.days))
    ]}


def get_inventory_structure(ctx: ToolContext, args: WarehouseArgs) -> dict:
    return paged(ctx.db, """
        SELECT w.id AS warehouse_id, w.name AS warehouse, w.code AS warehouse_code,
               COUNT(DISTINCT CASE WHEN i.quantity > 0 THEN p.id END) AS sku_count,
               COALESCE(SUM(i.quantity), 0) AS quantity,
               COALESCE(SUM(i.frozen_quantity), 0) AS frozen_quantity,
               COALESCE(SUM(i.available_quantity), 0) AS available_quantity,
               COALESCE(SUM(i.quantity * p.reference_price), 0) AS inventory_value
        FROM wms_warehouse w
        LEFT JOIN (wms_inventory i JOIN wms_product p ON p.id = i.product_id AND p.is_deleted = 0)
          ON i.warehouse_id = w.id AND i.is_deleted = 0
        WHERE w.is_deleted = 0
          AND (:warehouse IS NULL OR w.name LIKE :warehouse ESCAPE '!' OR w.code LIKE :warehouse ESCAPE '!')
        GROUP BY w.id, w.name, w.code ORDER BY inventory_value DESC, w.code
        """, {"warehouse": match(args.warehouse)}, args.limit, args.offset)


def get_order_status_stats(ctx: ToolContext, args: OrderStatsArguments) -> dict:
    branches = []
    for name in permitted_order_types(ctx.user, args.order_type):
        join, _, warehouse = order_warehouse_join(name)
        branches.append(f"""
            SELECT '{name}' AS order_type, o.status, COUNT(*) AS count
            FROM wms_{name}_order o {join}
            WHERE {order_filter(warehouse)} GROUP BY o.status
            """)
    sql = "\nUNION ALL\n".join(branches) + "\nORDER BY order_type, status"
    return paged(ctx.db, sql, {
        "warehouse": match(args.warehouse), "date_from": args.date_from, "date_to": args.date_to,
    }, args.limit, args.offset)


TOOLS = (
    ToolSpec("get_inout_trend", "按入账流水统计最近 7 天或 30 天的每日入库、出库数量",
             TrendArguments, ("wms:dashboard:view",), get_inout_trend),
    ToolSpec("get_inventory_structure", "按仓库统计库存数量、冻结量、可用量、SKU 数和参考库存价值；支持分页",
             WarehouseArgs, ("wms:dashboard:view",), get_inventory_structure),
    ToolSpec("get_order_status_stats", "统计有权查看的六类作业单据的状态分布；支持分页",
             OrderStatsArguments, ("wms:dashboard:view",), get_order_status_stats),
)
