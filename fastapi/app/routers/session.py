from collections import defaultdict
from typing import Annotated
from uuid import UUID, uuid4

from fastapi import APIRouter, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.database import DbSession
from app.core.security import CurrentUser
from app.models.memory import AiExecutionStep, AiMessage, AiRun, AiSession
from app.schemas import ApiResult, ExecutionStepView, MessageView, SessionCreate, SessionRename, SessionView

router = APIRouter(prefix="/ai/sessions", tags=["AI 会话"])


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
    # MySQL 的外键级联清理消息、运行记录及执行步骤。
    db.delete(row)
    db.commit()
    return ApiResult(data=None)


@router.get("/{session_id}/messages")
def get_session_messages(session_id: UUID, user: CurrentUser, db: DbSession) -> ApiResult[list[MessageView]]:
    row = owned_session(db, session_id, user.id)
    message_filter = (AiMessage.session_id == row.id, AiMessage.is_deleted == 0)
    messages = db.scalars(
        select(AiMessage).where(*message_filter).order_by(AiMessage.created_at, AiMessage.id)
    ).all()
    if not messages:
        return ApiResult(data=[])

    steps_by_message: dict[int, list[ExecutionStepView]] = defaultdict(list)
    for step in db.scalars(
        select(AiExecutionStep)
        .join(AiMessage, AiMessage.id == AiExecutionStep.message_id)
        .where(*message_filter, AiMessage.role == "assistant", AiExecutionStep.is_deleted == 0)
        .order_by(AiExecutionStep.message_id, AiExecutionStep.step_order, AiExecutionStep.id)
    ):
        steps_by_message[step.message_id].append(ExecutionStepView.model_validate(step))

    run_statuses = dict(
        db.execute(
            select(AiRun.assistant_message_id, AiRun.status)
            .where(AiRun.session_id == row.id, AiRun.is_deleted == 0)
        ).tuples().all()
    )
    return ApiResult(data=[
        MessageView(
            id=message.id,
            role=message.role,
            content=message.content,
            expert_count=message.expert_count,
            tool_call_count=message.tool_call_count,
            total_duration_ms=message.total_duration_ms,
            created_at=message.created_at,
            run_status=run_statuses.get(message.id),
            steps=steps_by_message.get(message.id, []),
        )
        for message in messages
    ])
