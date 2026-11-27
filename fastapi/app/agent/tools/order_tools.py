from dataclasses import dataclass
from typing import Annotated, Literal

from pydantic import Field

from app.agent.tools.common import (
    DateArgs, EntityId, PageArgs, Search, ToolContext, ToolError, ToolSpec, WarehouseArgs, match, paged,
)
from app.agent.tools.drafts import InboundDraft, OutboundDraft, create_inbound_draft, create_outbound_draft
from app.core.security import AuthenticatedUser

OrderType = Literal["inbound", "outbound", "transfer", "stocktake", "adjust", "freeze"]


@dataclass(frozen=True, slots=True)
class OrderTypeSpec:
    prefix: str
    item_columns: str


ORDER_TYPES: dict[str, OrderTypeSpec] = {
    "inbound": OrderTypeSpec("RK", "i.location_id, i.production_date, i.expire_date, i.quantity, i.unit_price"),
    "outbound": OrderTypeSpec("CK", "i.location_id, i.quantity, i.unit_price"),
    "transfer": OrderTypeSpec("YK", "i.from_location_id, i.to_location_id, i.quantity"),
    "stocktake": OrderTypeSpec("PD", "i.location_id, i.book_qty, i.actual_qty, i.diff_qty"),
    "adjust": OrderTypeSpec("TZ", "i.location_id, i.adjust_type, i.adjust_qty, i.reason"),
    "freeze": OrderTypeSpec("DJ", "i.location_id, i.op_type, i.quantity, i.reason"),
}
ORDER_PERMISSIONS = tuple(f"wms:{name}:list" for name in ORDER_TYPES)
DRAFT_PERMISSIONS = ("wms:inbound:add", "wms:outbound:add")


class OrderQuery(WarehouseArgs, DateArgs):
    order_type: OrderType | None = None
    order_no: Search | None = None
    status: Annotated[int, Field(ge=0, le=3)] | None = Field(
        default=None, description="0 草稿，1 待审核，2 已生效，3 已驳回"
    )


class DraftReferences(PageArgs):
    kind: Literal["warehouse", "location", "product", "supplier", "customer"]
    keyword: Search | None = None
    warehouse_id: EntityId | None = Field(default=None, description="限定指定仓库下的库位")


def permitted_order_types(user: AuthenticatedUser, order_type: str | None = None) -> tuple[str, ...]:
    permitted = tuple(name for name in ORDER_TYPES if f"wms:{name}:list" in user.permissions)
    if order_type is not None:
        if order_type not in permitted:
            raise ToolError("当前账号没有该类型单据的查询权限。")
        return (order_type,)
    if not permitted:
        raise ToolError("当前账号没有单据查询权限。")
    return permitted


def order_warehouse_join(kind: str) -> tuple[str, str, str]:
    if kind == "transfer":
        return (
            "JOIN wms_warehouse w ON w.id = o.from_warehouse_id AND w.is_deleted = 0 "
            "JOIN wms_warehouse dest ON dest.id = o.to_warehouse_id AND dest.is_deleted = 0",
            "dest.name",
            "OR dest.name LIKE :warehouse ESCAPE '!' OR dest.code LIKE :warehouse ESCAPE '!'",
        )
    return "JOIN wms_warehouse w ON w.id = o.warehouse_id AND w.is_deleted = 0", "NULL", ""


def order_filter(extra_warehouse: str) -> str:
    return f"""
        o.is_deleted = 0
        AND (:warehouse IS NULL OR w.name LIKE :warehouse ESCAPE '!' OR w.code LIKE :warehouse ESCAPE '!'
             {extra_warehouse})
        AND (:date_from IS NULL OR o.created_at >= :date_from)
        AND (:date_to IS NULL OR o.created_at < DATE_ADD(:date_to, INTERVAL 1 DAY))
        """


def query_orders(ctx: ToolContext, args: OrderQuery) -> dict:
    kind = args.order_type
    if kind is None and args.order_no is not None:
        kind = next((name for name, spec in ORDER_TYPES.items()
                     if args.order_no.upper().startswith(spec.prefix)), None)
    branches = []
    for name in permitted_order_types(ctx.user, kind):
        join, destination, warehouse = order_warehouse_join(name)
        source = "o.source" if name in ("inbound", "outbound") else "NULL"
        branches.append(f"""
            SELECT '{name}' AS order_type, o.id AS order_id, o.order_no, o.status, w.name AS warehouse,
                   {destination} AS destination, {source} AS source, o.remark, o.created_at, o.posted_at
            FROM wms_{name}_order o {join}
            WHERE {order_filter(warehouse)}
              AND (:status IS NULL OR o.status = :status)
              AND (:order_no IS NULL OR o.order_no = :order_no)
            """)
    sql = "\nUNION ALL\n".join(branches) + "\nORDER BY created_at DESC, order_type, order_id DESC"
    results = paged(ctx.db, sql, {
        "warehouse": match(args.warehouse), "date_from": args.date_from, "date_to": args.date_to,
        "status": args.status, "order_no": args.order_no,
    }, args.limit, args.offset)
    if args.order_no:
        for order in results["rows"]:
            name = order["order_type"]
            details = paged(ctx.db, f"""
                SELECT i.product_id, p.sku_code, p.name AS product_name, p.unit, i.batch_no,
                       {ORDER_TYPES[name].item_columns}
                FROM wms_{name}_item i JOIN wms_product p ON p.id = i.product_id
                WHERE i.order_id = :order_id AND i.is_deleted = 0 ORDER BY i.id
                """, {"order_id": order["order_id"]}, 50)
            order["items"] = details["rows"]
            order["items_truncated"] = details["truncated"]
    return results


REFERENCE_SELECT = {
    "warehouse": ("wms_warehouse", "id AS warehouse_id, code, name", "AND status = 1", "code", "name"),
    "location": ("wms_location", "id AS location_id, warehouse_id, area_id, code",
                 "AND status = 1 AND (:warehouse_id IS NULL OR warehouse_id = :warehouse_id)", "code", "code"),
    "product": ("wms_product", "id AS product_id, sku_code, name, unit, batch_managed, default_supplier_id",
                "", "sku_code", "name"),
    "supplier": ("wms_supplier", "id AS supplier_id, code, name", "", "code", "name"),
    "customer": ("wms_customer", "id AS customer_id, code, name", "", "code", "name"),
}


def query_draft_references(ctx: ToolContext, args: DraftReferences) -> dict:
    required = {"supplier": "wms:inbound:add", "customer": "wms:outbound:add"}.get(args.kind)
    if required and required not in ctx.user.permissions:
        raise ToolError("当前账号无法查询这类单据往来方。")

    table, columns, filters, code, name = REFERENCE_SELECT[args.kind]
    return paged(ctx.db, f"""
        SELECT {columns} FROM {table} WHERE is_deleted = 0 {filters}
          AND (:keyword IS NULL OR {code} LIKE :keyword ESCAPE '!' OR {name} LIKE :keyword ESCAPE '!')
        ORDER BY id
        """, {"keyword": match(args.keyword), "warehouse_id": args.warehouse_id}, args.limit, args.offset)


TOOLS = (
    ToolSpec("query_orders", "按类型、完整单号、状态、仓库和日期查六类单据；给出完整单号时返回明细；结果截断时按 next_offset 续查",
             OrderQuery, ORDER_PERMISSIONS, query_orders),
    ToolSpec("query_draft_references", "按名称或编码查询建单需要的仓库、库位、商品、供应商或客户 ID；支持分页",
             DraftReferences, DRAFT_PERMISSIONS, query_draft_references),
    ToolSpec("create_inbound_draft", "仅在服务端判定本轮已获建单授权时，按已核实的仓库、供应商和明细代建入库草稿",
             InboundDraft, ("wms:inbound:add",), create_inbound_draft, writes_draft=True),
    ToolSpec("create_outbound_draft", "仅在服务端判定本轮已获建单授权时，按已核实的 SKU、库位及批次校验库存并代建出库草稿",
             OutboundDraft, ("wms:outbound:add",), create_outbound_draft, writes_draft=True),
)
