from collections import defaultdict
from typing import Annotated
from uuid import UUID, uuid4

from fastapi import APIRouter, HTTPException, Query
from sqlalchemy import bindparam, select, text
from sqlalchemy.orm import Session

from app.core.database import DbSession
from app.core.security import CurrentUser
from app.models.memory import AiDraftOperation, AiExecutionStep, AiMessage, AiRun, AiSession
from app.schemas import (
    ApiResult, DraftView, ExecutionStepView, MessageView, SessionCreate, SessionRename, SessionView,
)

router = APIRouter(prefix="/ai/sessions", tags=["AI 会话"])
DRAFT_STATUS_LABELS = {0: "草稿", 1: "待审核", 2: "已生效", 3: "已驳回"}
ORDER_TABLES = {"inbound": "wms_inbound_order", "outbound": "wms_outbound_order"}


def owned_session(db: Session, session_id: UUID, user_id: int, *, lock: bool = False) -> AiSession:
    query = select(AiSession).where(
        AiSession.id == str(session_id),
        AiSession.user_id == user_id,
        AiSession.is_deleted == 0,
    )
    row = db.scalar(query.with_for_update() if lock else query)
    if row is None:
        raise HTTPException(404, "会话不存在")
    return row


def draft_cards(db: Session, run_ids: list[int]) -> dict[int, list[DraftView]]:
    """按运行记录装配草稿卡片。单据状态实时读取，人工审核后卡片会同步更新。"""
    if not run_ids:
        return {}
    operations = db.scalars(
        select(AiDraftOperation)
        .where(AiDraftOperation.run_id.in_(run_ids), AiDraftOperation.is_deleted == 0)
        .order_by(AiDraftOperation.created_at, AiDraftOperation.id)
    ).all()
    if not operations:
        return {}

    orders: dict[tuple[str, int], dict] = {}
    for kind, table in ORDER_TABLES.items():
        ids = [operation.order_id for operation in operations if operation.order_type == kind]
        if not ids:
            continue
        rows = db.execute(
            text(f"""
                SELECT id, status, source, posted_at, reject_reason FROM {table}
                WHERE id IN :ids
                """).bindparams(bindparam("ids", expanding=True)),
            {"ids": ids},
        ).mappings()
        for row in rows:
            orders[(kind, row["id"])] = dict(row)

    cards: dict[int, list[DraftView]] = defaultdict(list)
    for operation in operations:
        order = orders.get((operation.order_type, operation.order_id))
        if order is None:
            continue
        status = order["status"]
        cards[operation.run_id].append(DraftView(
            order_id=operation.order_id,
            order_no=operation.order_no,
            order_type=operation.order_type,
            status=status,
            status_label=DRAFT_STATUS_LABELS.get(status, "未知"),
            source=order["source"],
            item_count=operation.item_count,
            created_at=operation.created_at,
            posted_at=order["posted_at"],
            reject_reason=order["reject_reason"],
        ))
    return dict(cards)


@router.get("")
def list_sessions(
    user: CurrentUser,
    db: DbSession,
    limit: Annotated[int, Query(ge=1, le=100)] = 50,
    offset: Annotated[int, Query(ge=0)] = 0,
) -> ApiResult[list[SessionView]]:
    rows = db.scalars(
        select(AiSession)
        .where(AiSession.user_id == user.id, AiSession.is_deleted == 0)
        .order_by(AiSession.updated_at.desc(), AiSession.id.desc())
        .limit(limit)
        .offset(offset)
    )
    return ApiResult(data=[SessionView.model_validate(row) for row in rows])


@router.post("")
def create_session(user: CurrentUser, db: DbSession, body: SessionCreate | None = None) -> ApiResult[SessionView]:
    row = AiSession(
        id=str(uuid4()),
        user_id=user.id,
        created_by=user.id,
        title=(body or SessionCreate()).title,
    )
    db.add(row)
    db.commit()
    db.refresh(row)
    return ApiResult(data=SessionView.model_validate(row))


@router.put("/{session_id}")
def rename_session(session_id: UUID, body: SessionRename, user: CurrentUser, db: DbSession) -> ApiResult[SessionView]:
    row = owned_session(db, session_id, user.id, lock=True)
    row.title = body.title
    db.commit()
    db.refresh(row)
    return ApiResult(data=SessionView.model_validate(row))


@router.delete("/{session_id}")
def delete_session(session_id: UUID, user: CurrentUser, db: DbSession) -> ApiResult[None]:
    row = owned_session(db, session_id, user.id, lock=True)
    # MySQL 的外键级联清理消息、运行记录、执行步骤和草稿回执。
    db.delete(row)
    db.commit()
    return ApiResult(data=None)


@router.get("/{session_id}/messages")
def get_session_messages(
    session_id: UUID,
    user: CurrentUser,
    db: DbSession,
    limit: Annotated[int, Query(ge=1, le=500)] = 200,
    before_id: Annotated[int | None, Query(ge=1)] = None,
) -> ApiResult[list[MessageView]]:
    """按 id 升序返回最近的若干条消息；续查更早历史时传 before_id=当前首条消息 id。"""
    row = owned_session(db, session_id, user.id)
    message_filter = [AiMessage.session_id == row.id, AiMessage.is_deleted == 0]
    if before_id is not None:
        message_filter.append(AiMessage.id < before_id)
    messages = list(db.scalars(
        select(AiMessage).where(*message_filter).order_by(AiMessage.id.desc()).limit(limit)
    ).all())
    messages.reverse()
    if not messages:
        return ApiResult(data=[])

    message_ids = [message.id for message in messages]
    steps_by_message: dict[int, list[ExecutionStepView]] = defaultdict(list)
    for step in db.scalars(
        select(AiExecutionStep)
        .where(AiExecutionStep.message_id.in_(message_ids), AiExecutionStep.is_deleted == 0)
        .order_by(AiExecutionStep.message_id, AiExecutionStep.step_order, AiExecutionStep.id)
    ):
        steps_by_message[step.message_id].append(ExecutionStepView.model_validate(step))

    runs = {
        run.assistant_message_id: run
        for run in db.scalars(
            select(AiRun).where(AiRun.assistant_message_id.in_(message_ids), AiRun.is_deleted == 0)
        )
    }
    cards = draft_cards(db, [run.id for run in runs.values()])

    return ApiResult(data=[
        MessageView(
            id=message.id,
            role=message.role,
            content=message.content,
            expert_count=message.expert_count,
            tool_call_count=message.tool_call_count,
            total_duration_ms=message.total_duration_ms,
            created_at=message.created_at,
            run_id=runs[message.id].id if message.id in runs else None,
            run_status=runs[message.id].status if message.id in runs else None,
            steps=steps_by_message.get(message.id, []),
            drafts=cards.get(runs[message.id].id, []) if message.id in runs else [],
        )
        for message in messages
    ])
