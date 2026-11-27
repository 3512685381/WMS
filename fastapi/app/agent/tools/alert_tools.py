from typing import Annotated, Literal

from pydantic import Field

from app.agent.tools.common import ProductArgs, ToolContext, ToolSpec, paged, search_params


class LowStockArgs(ProductArgs):
    include_missing: bool = Field(
        default=False,
        description="是否把从未建过库存记录的 商品×仓库 组合按可用量 0 纳入预警，默认不纳入",
    )


class ExpiringArgs(ProductArgs):
    days: Annotated[int, Field(ge=1, le=365)] = 30
    scope: Literal["expiring", "expired", "all"] = Field(
        default="expiring", description="expiring 未来 N 天内到期，expired 已过期，all 两者都要",
    )


class ReplenishArgs(ProductArgs):
    consider_pending_inbound: bool = Field(
        default=True, description="是否把草稿和待审核入库单的数量计入供应，避免重复补货建议",
    )


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


def query_low_stock_alerts(ctx: ToolContext, args: LowStockArgs) -> dict:
    if args.include_missing:
        # 商品档案没有仓库属性，因此按每个启用仓库 × 每个启用商品评估，未建库存记录的组合按可用量 0 处理。
        return paged(ctx.db, """
            SELECT w.id AS warehouse_id, w.name AS warehouse, p.id AS product_id, p.sku_code,
                   p.name AS product_name, p.unit,
                   COALESCE(s.quantity, 0) AS quantity, COALESCE(s.frozen_quantity, 0) AS frozen_quantity,
                   COALESCE(s.available_quantity, 0) AS available_quantity,
                   p.min_stock, p.min_stock - COALESCE(s.available_quantity, 0) AS shortage,
                   (s.product_id IS NULL) AS no_stock_record
            FROM wms_product p
            JOIN wms_warehouse w ON w.status = 1 AND w.is_deleted = 0
            LEFT JOIN (
                SELECT warehouse_id, product_id, SUM(quantity) AS quantity,
                       SUM(frozen_quantity) AS frozen_quantity, SUM(available_quantity) AS available_quantity
                FROM wms_inventory WHERE is_deleted = 0 GROUP BY warehouse_id, product_id
            ) s ON s.product_id = p.id AND s.warehouse_id = w.id
            WHERE p.is_deleted = 0
              AND (:warehouse IS NULL OR w.name LIKE :warehouse ESCAPE '!' OR w.code LIKE :warehouse ESCAPE '!')
              AND (:keyword IS NULL OR p.name LIKE :keyword ESCAPE '!' OR p.sku_code LIKE :keyword ESCAPE '!')
              AND COALESCE(s.available_quantity, 0) < p.min_stock
            ORDER BY shortage DESC, w.id, p.id
            """, search_params(args), args.limit, args.offset)
    return paged(ctx.db, STOCK_TOTALS + """
        SELECT warehouse_id, warehouse, product_id, sku_code, product_name, unit, quantity, frozen_quantity,
               available_quantity, min_stock, min_stock - available_quantity AS shortage
        FROM stock WHERE available_quantity < min_stock
        ORDER BY shortage DESC, warehouse_id, product_id
        """, search_params(args), args.limit, args.offset)


def query_overstock_alerts(ctx: ToolContext, args: ProductArgs) -> dict:
    return paged(ctx.db, STOCK_TOTALS + """
        SELECT warehouse_id, warehouse, product_id, sku_code, product_name, unit, quantity,
               available_quantity, max_stock, quantity - max_stock AS excess
        FROM stock WHERE max_stock > 0 AND quantity > max_stock
        ORDER BY excess DESC, warehouse_id, product_id
        """, search_params(args), args.limit, args.offset)


def query_expiring_alerts(ctx: ToolContext, args: ExpiringArgs) -> dict:
    return paged(ctx.db, """
        SELECT w.name AS warehouse, l.code AS location_code, p.sku_code, p.name AS product_name,
               p.unit, i.batch_no, i.quantity, i.available_quantity, i.expire_date,
               DATEDIFF(i.expire_date, CURRENT_DATE()) AS days_left,
               CASE WHEN i.expire_date < CURRENT_DATE() THEN 'expired' ELSE 'expiring' END AS expiry_state
        FROM wms_inventory i
        JOIN wms_warehouse w ON w.id = i.warehouse_id AND w.is_deleted = 0
        JOIN wms_product p ON p.id = i.product_id AND p.is_deleted = 0
        JOIN wms_location l ON l.id = i.location_id AND l.warehouse_id = w.id AND l.is_deleted = 0
        WHERE i.is_deleted = 0 AND i.quantity > 0 AND i.expire_date IS NOT NULL
          AND (
              :scope = 'all'
              OR (:scope = 'expiring' AND i.expire_date BETWEEN CURRENT_DATE()
                  AND DATE_ADD(CURRENT_DATE(), INTERVAL :days DAY))
              OR (:scope = 'expired' AND i.expire_date < CURRENT_DATE())
          )
          AND (:warehouse IS NULL OR w.name LIKE :warehouse ESCAPE '!' OR w.code LIKE :warehouse ESCAPE '!')
          AND (:keyword IS NULL OR p.name LIKE :keyword ESCAPE '!' OR p.sku_code LIKE :keyword ESCAPE '!')
        ORDER BY i.expire_date, i.id
        """, {**search_params(args), "days": args.days, "scope": args.scope}, args.limit, args.offset)


def suggest_replenishment(ctx: ToolContext, args: ReplenishArgs) -> dict:
    return paged(ctx.db, STOCK_TOTALS + """
        , pending AS (
            SELECT i.warehouse_id, i.product_id, SUM(i.quantity) AS pending_quantity
            FROM wms_inbound_item i
            JOIN wms_inbound_order o ON o.id = i.order_id AND o.is_deleted = 0 AND o.status IN (0, 1)
            WHERE i.is_deleted = 0
            GROUP BY i.warehouse_id, i.product_id
        )
        SELECT t.warehouse_id, t.warehouse, t.product_id, t.sku_code, t.product_name, t.unit,
               t.available_quantity, t.min_stock, t.max_stock,
               t.min_stock - t.available_quantity AS shortage,
               COALESCE(pd.pending_quantity, 0) * :pending_weight AS pending_inbound_quantity,
               GREATEST(GREATEST(t.max_stock, t.min_stock) - t.available_quantity
                        - COALESCE(pd.pending_quantity, 0) * :pending_weight, 0) AS suggested_quantity,
               s.id AS supplier_id, s.name AS supplier_name, s.code AS supplier_code
        FROM stock t
        LEFT JOIN pending pd ON pd.warehouse_id = t.warehouse_id AND pd.product_id = t.product_id
        LEFT JOIN wms_supplier s ON s.id = t.default_supplier_id AND s.is_deleted = 0
        WHERE t.available_quantity + COALESCE(pd.pending_quantity, 0) * :pending_weight < t.min_stock
        ORDER BY shortage DESC, t.warehouse_id, t.product_id
        """, {**search_params(args), "pending_weight": 1 if args.consider_pending_inbound else 0},
        args.limit, args.offset)


TOOLS = (
    ToolSpec("query_low_stock_alerts", "按仓库和商品汇总，找出可用量低于安全下限的商品；可用量按 0 兜底时需显式开启",
             LowStockArgs, ("wms:alert:list",), query_low_stock_alerts),
    ToolSpec("query_overstock_alerts", "按仓库和商品汇总，找出在库量高于安全上限的商品",
             ProductArgs, ("wms:alert:list",), query_overstock_alerts),
    ToolSpec("query_expiring_alerts", "查询未来指定天数内到期或已过期且仍有在库量的批次，两者可分开查",
             ExpiringArgs, ("wms:alert:list",), query_expiring_alerts),
    ToolSpec("suggest_replenishment", "查询低库存缺口，按安全上限、可用量和待入库量计算建议补货量",
             ReplenishArgs, ("wms:alert:list",), suggest_replenishment),
)
