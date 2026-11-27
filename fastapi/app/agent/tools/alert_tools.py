from typing import Annotated

from pydantic import Field

from app.agent.tools.common import ProductArgs, ToolContext, ToolSpec, paged, search_params


class ExpiringArgs(ProductArgs):
    days: Annotated[int, Field(ge=1, le=365)] = 30


STOCK_TOTALS = """
    WITH stock AS (
        SELECT w.id AS warehouse_id, w.name AS warehouse, p.id AS product_id, p.sku_code, p.name AS product_name,
               p.unit, p.min_stock, p.max_stock, p.default_supplier_id,
               SUM(i.quantity) AS quantity, SUM(i.frozen_quantity) AS frozen_quantity,
               SUM(i.available_quantity) AS available_quantity
        FROM wms_inventory i
        JOIN wms_product p ON p.id = i.product_id AND p.is_deleted = 0
        JOIN wms_warehouse w ON w.id = i.warehouse_id AND w.is_deleted = 0
        WHERE i.is_deleted = 0
          AND (:warehouse IS NULL OR w.name LIKE :warehouse ESCAPE '!' OR w.code LIKE :warehouse ESCAPE '!')
          AND (:keyword IS NULL OR p.name LIKE :keyword ESCAPE '!' OR p.sku_code LIKE :keyword ESCAPE '!')
        GROUP BY w.id, w.name, p.id, p.sku_code, p.name, p.unit, p.min_stock, p.max_stock, p.default_supplier_id
    )
    """


def query_low_stock_alerts(ctx: ToolContext, args: ProductArgs) -> dict:
    return paged(ctx.db, STOCK_TOTALS + """
        SELECT warehouse_id, warehouse, product_id, sku_code, product_name, unit, quantity, frozen_quantity,
               available_quantity, min_stock, min_stock - available_quantity AS shortage
        FROM stock WHERE available_quantity < min_stock
        ORDER BY shortage DESC, warehouse_id, product_id
        """, search_params(args), args.limit)


def query_overstock_alerts(ctx: ToolContext, args: ProductArgs) -> dict:
    return paged(ctx.db, STOCK_TOTALS + """
        SELECT warehouse_id, warehouse, product_id, sku_code, product_name, unit, quantity,
               available_quantity, max_stock, quantity - max_stock AS excess
        FROM stock WHERE max_stock > 0 AND quantity > max_stock
        ORDER BY excess DESC, warehouse_id, product_id
        """, search_params(args), args.limit)


def query_expiring_alerts(ctx: ToolContext, args: ExpiringArgs) -> dict:
    return paged(ctx.db, """
        SELECT w.name AS warehouse, l.code AS location_code, p.sku_code, p.name AS product_name,
               p.unit, i.batch_no, i.quantity, i.available_quantity, i.expire_date,
               DATEDIFF(i.expire_date, CURRENT_DATE()) AS days_left
        FROM wms_inventory i
        JOIN wms_warehouse w ON w.id = i.warehouse_id AND w.is_deleted = 0
        JOIN wms_product p ON p.id = i.product_id AND p.is_deleted = 0
        JOIN wms_location l ON l.id = i.location_id AND l.warehouse_id = w.id AND l.is_deleted = 0
        WHERE i.is_deleted = 0 AND i.quantity > 0
          AND i.expire_date BETWEEN CURRENT_DATE() AND DATE_ADD(CURRENT_DATE(), INTERVAL :days DAY)
          AND (:warehouse IS NULL OR w.name LIKE :warehouse ESCAPE '!' OR w.code LIKE :warehouse ESCAPE '!')
          AND (:keyword IS NULL OR p.name LIKE :keyword ESCAPE '!' OR p.sku_code LIKE :keyword ESCAPE '!')
        ORDER BY i.expire_date, i.id
        """, {**search_params(args), "days": args.days}, args.limit)


def suggest_replenishment(ctx: ToolContext, args: ProductArgs) -> dict:
    return paged(ctx.db, STOCK_TOTALS + """
        SELECT t.warehouse_id, t.warehouse, t.product_id, t.sku_code, t.product_name, t.unit,
               t.available_quantity, t.min_stock, t.max_stock, t.min_stock - t.available_quantity AS shortage,
               GREATEST(t.max_stock, t.min_stock) - t.available_quantity AS suggested_quantity,
               s.id AS supplier_id, s.name AS supplier_name, s.code AS supplier_code
        FROM stock t
        LEFT JOIN wms_supplier s ON s.id = t.default_supplier_id AND s.is_deleted = 0
        WHERE t.available_quantity < t.min_stock
        ORDER BY shortage DESC, t.warehouse_id, t.product_id
        """, search_params(args), args.limit)


TOOLS = (
    ToolSpec("query_low_stock_alerts", "按仓库和商品汇总，找出可用量低于安全下限的商品",
             ProductArgs, ("wms:alert:list",), query_low_stock_alerts),
    ToolSpec("query_overstock_alerts", "按仓库和商品汇总，找出在库量高于安全上限的商品",
             ProductArgs, ("wms:alert:list",), query_overstock_alerts),
    ToolSpec("query_expiring_alerts", "查询未来指定天数内到期且仍有在库量的批次",
             ExpiringArgs, ("wms:alert:list",), query_expiring_alerts),
    ToolSpec("suggest_replenishment", "查询低库存缺口并按安全上限和默认供应商计算建议补货量",
             ProductArgs, ("wms:alert:list",), suggest_replenishment),
)
