from typing import Literal

from pydantic import Field

from app.agent.tools.common import (
    DateArgs, ProductArgs, Search, ToolContext, ToolSpec, WarehouseArgs, match, paged, search_params,
)

InventoryType = Literal["INBOUND", "OUTBOUND", "TRANSFER_IN", "TRANSFER_OUT", "ADJUST", "FREEZE", "UNFREEZE"]


class InventoryQuery(ProductArgs):
    batch_no: Search | None = None
    location: Search | None = Field(default=None, description="库位编码")


class InventoryLogQuery(ProductArgs, DateArgs):
    order_no: Search | None = None
    biz_type: InventoryType | None = None


class WarehouseQuery(WarehouseArgs):
    location: Search | None = Field(default=None, description="库位编码")


def query_realtime_inventory(ctx: ToolContext, args: InventoryQuery) -> dict:
    return paged(ctx.db, """
        SELECT i.warehouse_id, w.name AS warehouse, w.code AS warehouse_code,
               i.location_id, l.code AS location_code, i.product_id, p.name AS product_name,
               p.sku_code, p.unit, i.batch_no, i.production_date, i.expire_date,
               i.quantity, i.frozen_quantity, i.available_quantity, p.min_stock, p.max_stock
        FROM wms_inventory i
        JOIN wms_warehouse w ON w.id = i.warehouse_id AND w.is_deleted = 0
        JOIN wms_location l ON l.id = i.location_id AND l.warehouse_id = w.id AND l.is_deleted = 0
        JOIN wms_product p ON p.id = i.product_id AND p.is_deleted = 0
        WHERE i.is_deleted = 0
          AND (:warehouse IS NULL OR w.name LIKE :warehouse ESCAPE '!' OR w.code LIKE :warehouse ESCAPE '!')
          AND (:keyword IS NULL OR p.name LIKE :keyword ESCAPE '!' OR p.sku_code LIKE :keyword ESCAPE '!')
          AND (:location IS NULL OR l.code LIKE :location ESCAPE '!')
          AND (:batch_no IS NULL OR i.batch_no = :batch_no)
        ORDER BY w.id, p.sku_code, l.code, i.batch_no, i.id
        """, {**search_params(args), "location": match(args.location), "batch_no": args.batch_no}, args.limit)


def query_inventory_logs(ctx: ToolContext, args: InventoryLogQuery) -> dict:
    return paged(ctx.db, """
        SELECT g.order_no, g.biz_type, g.created_at, w.name AS warehouse, l.code AS location_code,
               p.sku_code, p.name AS product_name, p.unit, g.batch_no, g.before_qty, g.change_qty, g.after_qty,
               g.before_frozen_qty, g.change_frozen_qty, g.after_frozen_qty
        FROM wms_inventory_log g
        JOIN wms_warehouse w ON w.id = g.warehouse_id AND w.is_deleted = 0
        JOIN wms_location l ON l.id = g.location_id AND l.warehouse_id = w.id AND l.is_deleted = 0
        JOIN wms_product p ON p.id = g.product_id AND p.is_deleted = 0
        WHERE g.is_deleted = 0
          AND (:warehouse IS NULL OR w.name LIKE :warehouse ESCAPE '!' OR w.code LIKE :warehouse ESCAPE '!')
          AND (:keyword IS NULL OR p.name LIKE :keyword ESCAPE '!' OR p.sku_code LIKE :keyword ESCAPE '!')
          AND (:order_no IS NULL OR g.order_no = :order_no)
          AND (:biz_type IS NULL OR g.biz_type = :biz_type)
          AND (:date_from IS NULL OR g.created_at >= :date_from)
          AND (:date_to IS NULL OR g.created_at < DATE_ADD(:date_to, INTERVAL 1 DAY))
        ORDER BY g.created_at DESC, g.id DESC
        """, {
        **search_params(args), "order_no": args.order_no, "biz_type": args.biz_type,
        "date_from": args.date_from, "date_to": args.date_to,
    }, args.limit)


def query_warehouse_structure(ctx: ToolContext, args: WarehouseQuery) -> dict:
    return paged(ctx.db, """
        SELECT w.id AS warehouse_id, w.name AS warehouse, w.code AS warehouse_code, w.status AS warehouse_status,
               a.id AS area_id, a.code AS area_code, a.name AS area_name, a.area_type,
               l.id AS location_id, l.code AS location_code, l.status AS location_status
        FROM wms_warehouse w
        LEFT JOIN wms_area a ON a.warehouse_id = w.id AND a.is_deleted = 0
        LEFT JOIN wms_location l ON l.warehouse_id = w.id AND l.area_id = a.id AND l.is_deleted = 0
        WHERE w.is_deleted = 0
          AND (:warehouse IS NULL OR w.name LIKE :warehouse ESCAPE '!' OR w.code LIKE :warehouse ESCAPE '!')
          AND (:location IS NULL OR l.code LIKE :location ESCAPE '!')
        ORDER BY w.code, a.code, l.code
        """, {"warehouse": match(args.warehouse), "location": match(args.location)}, args.limit)


TOOLS = (
    ToolSpec("query_realtime_inventory", "查询指定仓库、商品、库位或批次的在库、冻结和可用量", InventoryQuery,
             ("wms:inventory:list",), query_realtime_inventory),
    ToolSpec("query_inventory_logs", "查询库存入出库、移库、调整和冻结流水", InventoryLogQuery,
             ("wms:inventory:logs",), query_inventory_logs),
    ToolSpec("query_warehouse_structure", "查询仓库、库区和库位结构及编码", WarehouseQuery,
             ("wms:warehouse:list", "wms:inventory:list"), query_warehouse_structure),
)
