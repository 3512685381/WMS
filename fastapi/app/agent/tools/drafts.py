import hashlib
import json
from collections import defaultdict
from datetime import date, timedelta
from decimal import Decimal
from typing import Annotated, Literal

from pydantic import Field, StringConstraints
from sqlalchemy import text

from app.agent.tools.common import EntityId, ToolArguments, ToolContext, ToolError

DraftKind = Literal["inbound", "outbound"]
Quantity = Annotated[Decimal, Field(gt=0, max_digits=18, decimal_places=3)]
Price = Annotated[Decimal, Field(ge=0, max_digits=18, decimal_places=4)]
BatchNo = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=64)]
ZERO = Decimal(0)


class DraftItem(ToolArguments):
    product_id: EntityId
    location_id: EntityId
    quantity: Quantity
    batch_no: BatchNo | None = None
    unit_price: Price = ZERO


class InboundItem(DraftItem):
    production_date: date | None = None
    expire_date: date | None = None


class DraftArguments(ToolArguments):
    warehouse_id: EntityId
    remark: Annotated[str, StringConstraints(strip_whitespace=True, max_length=500)] | None = None


class InboundDraft(DraftArguments):
    supplier_id: EntityId
    items: Annotated[list[InboundItem], Field(min_length=1, max_length=50)]


class OutboundDraft(DraftArguments):
    customer_id: EntityId | None = None
    items: Annotated[list[DraftItem], Field(min_length=1, max_length=50)]


def _select(ctx: ToolContext, query: str, **params):
    return ctx.db.execute(text(query), params).mappings().one_or_none()


def _request_key(ctx: ToolContext, kind: DraftKind, args: DraftArguments) -> str:
    data = args.model_dump(mode="json")
    for item, serialized in zip(args.items, data["items"], strict=True):
        serialized["quantity"] = format(item.quantity.normalize(), "f")
        serialized["unit_price"] = format(item.unit_price.normalize(), "f")
    data["items"].sort(key=lambda item: json.dumps(item, sort_keys=True))
    material = json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    digest = hashlib.sha256(material.encode()).hexdigest()
    return f"ai:{ctx.run_id}:{kind}:{digest}"


def _prepare_number(ctx: ToolContext, kind: DraftKind, request_key: str) -> tuple[dict | None, dict]:
    prefix = "RK" if kind == "inbound" else "CK"
    business_date = ctx.db.scalar(text("SELECT CURRENT_DATE()"))
    sequence = {"prefix": prefix, "business_date": business_date}
    ctx.db.execute(text("""
        INSERT INTO wms_order_sequence (order_prefix, biz_date, last_no)
        VALUES (:prefix, :business_date, 0)
        ON DUPLICATE KEY UPDATE last_no = wms_order_sequence.last_no
        """), sequence)
    last_no = ctx.db.scalar(text("""
        SELECT last_no FROM wms_order_sequence
        WHERE order_prefix = :prefix AND biz_date = :business_date FOR UPDATE
        """), sequence)

    existing = _select(ctx, f"""
        SELECT id AS order_id, order_no, status, source, is_deleted FROM wms_{kind}_order
        WHERE created_by = :user_id AND request_key = :request_key FOR SHARE
        """, user_id=ctx.user.id, request_key=request_key)
    if existing is not None:
        record = dict(existing)
        if record.pop("is_deleted"):
            raise ToolError("同一请求此前生成的单据已删除。")
        return {**record, "reused": True}, {}

    number_start = f"{prefix}{business_date:%Y%m%d}"
    greatest = ctx.db.scalar(text(f"""
        SELECT order_no FROM wms_{kind}_order
        WHERE order_no LIKE :number_prefix ORDER BY order_no DESC LIMIT 1 FOR SHARE
        """), {"number_prefix": number_start + "%"})
    next_no = max(last_no, int(greatest[-4:]) if greatest else 0) + 1
    if next_no > 9999:
        raise ToolError("该类型今日单号已用完。")
    return None, {**sequence, "next_no": next_no, "order_no": f"{number_start}{next_no:04d}"}


def _validate_header(ctx: ToolContext, warehouse_id: int, partner: str, partner_id: int | None) -> None:
    warehouse = _select(ctx, """
        SELECT id FROM wms_warehouse WHERE id = :id AND status = 1 AND is_deleted = 0 FOR SHARE
        """, id=warehouse_id)
    if warehouse is None:
        raise ToolError("指定仓库不存在或已停用。", warehouse_id=warehouse_id)
    if partner_id is not None and _select(ctx, f"""
        SELECT id FROM wms_{partner} WHERE id = :id AND is_deleted = 0 FOR SHARE
        """, id=partner_id) is None:
        raise ToolError("指定供应商或客户不存在。", partner_id=partner_id)


def _build_items(ctx: ToolContext, args: InboundDraft | OutboundDraft) -> list[dict]:
    locations = {}
    products = {}
    rows = []

    for item in args.items:
        if item.location_id not in locations:
            locations[item.location_id] = _select(ctx, """
                SELECT id FROM wms_location
                WHERE id = :id AND warehouse_id = :warehouse_id AND status = 1 AND is_deleted = 0 FOR SHARE
                """, id=item.location_id, warehouse_id=args.warehouse_id)
        if locations[item.location_id] is None:
            raise ToolError("库位不存在、已停用或不属于指定仓库。", location_id=item.location_id)

        if item.product_id not in products:
            products[item.product_id] = _select(ctx, """
                SELECT id, batch_managed, shelf_life_days FROM wms_product
                WHERE id = :id AND is_deleted = 0 FOR SHARE
                """, id=item.product_id)
        product = products[item.product_id]
        if product is None:
            raise ToolError("商品不存在。", product_id=item.product_id)
        if product["batch_managed"]:
            if item.batch_no is None or item.batch_no.upper() == "NO_BATCH":
                raise ToolError("该商品须填写实际批次号。", product_id=item.product_id)
            batch_no = item.batch_no
        else:
            if item.batch_no is not None and item.batch_no.upper() != "NO_BATCH":
                raise ToolError("该商品不按批次管理。", product_id=item.product_id)
            batch_no = "NO_BATCH"

        row = {"warehouse_id": args.warehouse_id, "product_id": item.product_id, "location_id": item.location_id,
               "batch_no": batch_no, "quantity": item.quantity, "unit_price": item.unit_price,
               "created_by": ctx.user.id}
        if isinstance(item, InboundItem):
            expiration = item.expire_date
            if expiration is None and item.production_date is not None and product["shelf_life_days"]:
                try:
                    expiration = item.production_date + timedelta(days=product["shelf_life_days"])
                except OverflowError:
                    raise ToolError("按保质期计算出的效期超出范围。", product_id=item.product_id) from None
            if expiration and item.production_date and expiration < item.production_date:
                raise ToolError("效期不得早于生产日期。", product_id=item.product_id)
            row.update(production_date=item.production_date, expire_date=expiration)
        rows.append(row)
    return rows


def _check_available(ctx: ToolContext, rows: list[dict]) -> None:
    dimensions = defaultdict(list)
    for row in rows:
        key = (row["product_id"], row["location_id"], row["batch_no"])
        dimensions[key].append(row)

    required = defaultdict(lambda: ZERO)
    balances = {}
    for (product_id, location_id, batch_no), items in sorted(dimensions.items()):
        inventory = _select(ctx, """
            SELECT id, batch_no, available_quantity FROM wms_inventory
            WHERE warehouse_id = :warehouse_id AND location_id = :location_id
              AND product_id = :product_id AND batch_no = :batch_no AND is_deleted = 0 FOR SHARE
            """, warehouse_id=items[0]["warehouse_id"], location_id=location_id,
            product_id=product_id, batch_no=batch_no)
        key = inventory["id"] if inventory else (product_id, location_id, batch_no)
        required[key] += sum((item["quantity"] for item in items), ZERO)
        balances[key] = {
            "product_id": product_id,
            "location_id": location_id,
            "batch_no": inventory["batch_no"] if inventory else batch_no,
            "available_quantity": inventory["available_quantity"] if inventory else ZERO,
        }
        if inventory:
            for item in items:
                item["batch_no"] = inventory["batch_no"]

    shortages = []
    for key, qty in required.items():
        balance = balances[key]
        if balance["available_quantity"] < qty:
            shortages.append({**balance, "requested_quantity": qty, "shortage": qty - balance["available_quantity"]})
    if shortages:
        raise ToolError("部分批次可用库存不足，出库草稿未保存。", shortages=shortages)


def _create_draft(ctx: ToolContext, kind: DraftKind, args: InboundDraft | OutboundDraft) -> dict:
    request_key = _request_key(ctx, kind, args)
    existing, number = _prepare_number(ctx, kind, request_key)
    if existing is not None:
        return existing

    inbound = isinstance(args, InboundDraft)
    partner = "supplier" if inbound else "customer"
    partner_id = args.supplier_id if inbound else args.customer_id
    _validate_header(ctx, args.warehouse_id, partner, partner_id)
    rows = _build_items(ctx, args)
    if not inbound:
        _check_available(ctx, rows)

    ctx.db.execute(text("""
        UPDATE wms_order_sequence SET last_no = :next_no
        WHERE order_prefix = :prefix AND biz_date = :business_date
        """), number)
    inserted = ctx.db.execute(text(f"""
        INSERT INTO wms_{kind}_order
            (order_no, warehouse_id, {partner}_id, status, source, request_key, remark, created_by)
        VALUES (:order_no, :warehouse_id, :partner_id, 0, 'AI_AGENT', :request_key, :remark, :created_by)
        """), {
        "order_no": number["order_no"], "warehouse_id": args.warehouse_id, "partner_id": partner_id,
        "request_key": request_key, "remark": args.remark, "created_by": ctx.user.id,
    })
    order_id = inserted.lastrowid
    date_columns = ", production_date, expire_date" if inbound else ""
    date_values = ", :production_date, :expire_date" if inbound else ""
    ctx.db.execute(text(f"""
        INSERT INTO wms_{kind}_item
            (order_id, warehouse_id, product_id, location_id, batch_no, quantity, unit_price, created_by{date_columns})
        VALUES (:order_id, :warehouse_id, :product_id, :location_id, :batch_no, :quantity, :unit_price,
                :created_by{date_values})
        """), [{**row, "order_id": order_id} for row in rows])
    return {"order_id": order_id, "order_no": number["order_no"], "status": 0, "source": "AI_AGENT",
            "item_count": len(rows), "reused": False}


def create_inbound_draft(ctx: ToolContext, args: InboundDraft) -> dict:
    return _create_draft(ctx, "inbound", args)


def create_outbound_draft(ctx: ToolContext, args: OutboundDraft) -> dict:
    return _create_draft(ctx, "outbound", args)
