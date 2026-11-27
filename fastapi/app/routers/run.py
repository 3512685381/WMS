from datetime import timedelta

from fastapi import APIRouter, HTTPException
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.agent.run_state import RUN_LEASE_SECONDS, sweep_stale_runs
from app.core.database import DbSession
from app.core.security import CurrentUser
from app.models.memory import AiExecutionStep, AiMessage, AiRun, AiSession
from app.routers.session import draft_cards
from app.schemas import ApiResult, ExecutionStepView, RunView

router = APIRouter(prefix="/ai/runs", tags=["AI 运行"])


def owned_run(db: Session, run_id: int, user_id: int) -> AiRun:
    run = db.scalar(
        select(AiRun)
        .join(AiSession, AiSession.id == AiRun.session_id)
        .where(
            AiRun.id == run_id,
            AiRun.is_deleted == 0,
            AiSession.user_id == user_id,
            AiSession.is_deleted == 0,
        )
    )
    if run is None:
        raise HTTPException(404, "运行记录不存在")
    return run


def run_view(db: Session, run: AiRun) -> RunView:
    message = db.get(AiMessage, run.assistant_message_id)
    steps = db.scalars(
        select(AiExecutionStep)
        .where(AiExecutionStep.message_id == run.assistant_message_id, AiExecutionStep.is_deleted == 0)
        .order_by(AiExecutionStep.step_order, AiExecutionStep.id)
    ).all()
    drafts = draft_cards(db, [run.id]).get(run.id, [])
    return RunView(
        run_id=run.id,
        session_id=run.session_id,
        message_id=run.assistant_message_id,
        user_message_id=run.user_message_id,
        status=run.status,
        operation_id=run.operation_id,
        content=message.content if message else "",
        error_message=run.error_message,
        expert_count=message.expert_count if message else 0,
        tool_call_count=message.tool_call_count if message else 0,
        total_duration_ms=message.total_duration_ms if message else 0,
        started_at=run.started_at,
        heartbeat_at=run.heartbeat_at,
        finished_at=run.finished_at,
        steps=[ExecutionStepView.model_validate(step) for step in steps],
        drafts=drafts,
    )


@router.get("/{run_id}")
def get_run(run_id: int, user: CurrentUser, db: DbSession) -> ApiResult[RunView]:
    """页面重连后读取已有运行：已完成则回放文本、步骤和草稿，不重新执行建单。"""
    run = owned_run(db, run_id, user.id)
    if run.status == "running":
        sweep_stale_runs(db, run.session_id)
        db.commit()
        db.refresh(run)
    return ApiResult(data=run_view(db, run))


@router.post("/{run_id}/cancel")
def cancel_run(run_id: int, user: CurrentUser, db: DbSession) -> ApiResult[RunView]:
    """收敛失去执行者的运行。仍有心跳的运行交给原请求处理，避免伪造取消。"""
    run = owned_run(db, run_id, user.id)
    if run.status != "running":
        return ApiResult(data=run_view(db, run))

    now = db.scalar(select(func.now(3)))
    deadline = now - timedelta(seconds=RUN_LEASE_SECONDS)
    if run.heartbeat_at >= deadline:
        raise HTTPException(409, "本轮仍在执行，请在页面上停止生成，或稍后再试。")
    run.status = "interrupted"
    run.finished_at = now
    db.commit()
    db.refresh(run)
    return ApiResult(data=run_view(db, run))
