import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import aclosing
from dataclasses import asdict, dataclass, field, replace
from datetime import timedelta
from time import monotonic
from typing import Annotated
from uuid import UUID

from anyio import CancelScope
from fastapi import APIRouter, Depends, HTTPException
from fastapi.concurrency import run_in_threadpool
from fastapi.sse import EventSourceResponse, ServerSentEvent
from pydantic import BaseModel, StringConstraints
from sqlalchemy import func, select
from sqlalchemy.exc import SQLAlchemyError

from app.agent.agents.main_agent import answer
from app.agent.events import Timeline, current_timeline, elapsed_ms
from app.agent.llm import GENERATION_TIMEOUT_SECONDS, ChatModel, ModelError, ModelOption, list_chat_models
from app.agent.registry import AgentRequest
from app.core.database import DbSession, SessionLocal
from app.core.security import AuthenticatedUser, CurrentUser
from app.models.memory import AiExecutionStep, AiMessage, AiRun, AiSession
from app.routers.session import owned_session
from app.schemas import ApiResult

router = APIRouter(prefix="/ai/chat", tags=["AI 对话"])
logger = logging.getLogger(__name__)
CONTEXT_CHARS = 16000


class ChatRequest(BaseModel):
    session_id: UUID
    query: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=4000)]
    model: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=256)] | None = None


class ModelOptions(BaseModel):
    default_model: str
    models: list[ModelOption]


@dataclass
class Turn:
    model: ChatModel
    user: AuthenticatedUser
    session_id: str
    run_id: int
    user_message_id: int
    assistant_message_id: int
    history: list[dict[str, str]]
    started: float = field(default_factory=monotonic)
    pieces: list[str] = field(default_factory=list)
    timeline: Timeline = field(default_factory=Timeline)
    producer: asyncio.Task[str | None] | None = None
    saved: bool = False

    def stats(self, duration_ms: int) -> dict:
        return {
            "message_id": self.assistant_message_id,
            "expert_count": len(self.timeline.experts),
            "tool_call_count": self.timeline.tool_calls,
            "total_duration_ms": duration_ms,
        }


def recent_history(db: DbSession, session_id: str) -> list[dict[str, str]]:
    rows = db.execute(
        select(AiMessage.role, func.right(AiMessage.content, CONTEXT_CHARS))
        .where(AiMessage.session_id == session_id, AiMessage.is_deleted == 0, AiMessage.content != "")
        .order_by(AiMessage.created_at.desc(), AiMessage.id.desc())
        .limit(20)
        .with_for_update(read=True)
    ).all()
    remaining = CONTEXT_CHARS
    messages = []
    for role, content in rows:
        if remaining == 0:
            break
        message = content[-remaining:]
        messages.append({"role": role, "content": message})
        remaining -= len(message)
    return list(reversed(messages))


def save_turn(turn: Turn, status: str, duration_ms: int, error: str | None = None) -> bool:
    with SessionLocal.begin() as db:
        session = db.get(AiSession, turn.session_id, with_for_update=True)
        run = db.get(AiRun, turn.run_id, with_for_update=True)
        if session is None or session.is_deleted or run is None or run.is_deleted or run.status != "running":
            return False

        assistant = db.get(AiMessage, turn.assistant_message_id)
        if assistant is None:
            return False
        assistant.content = "".join(turn.pieces)
        assistant.expert_count = len(turn.timeline.experts)
        assistant.tool_call_count = turn.timeline.tool_calls
        assistant.total_duration_ms = duration_ms
        for step in turn.timeline.steps:
            fields = asdict(step)
            fields["created_at"] = step.created_at.replace(tzinfo=None)
            db.add(AiExecutionStep(message_id=assistant.id, created_by=turn.user.id, **fields))
        run.status = status
        run.finished_at = func.now(3)
        run.error_message = error
        session.updated_at = func.now(3)
    return True


def configured_model() -> ChatModel:
    try:
        return ChatModel.from_settings()
    except ModelError as error:
        raise HTTPException(503, str(error)) from None


def preferred_model(model: ChatModel, options: tuple[ModelOption, ...]) -> str:
    if options and not any(option.id == model.model for option in options):
        return options[0].id
    return model.model


@router.get("/models")
async def model_options(_user: CurrentUser) -> ApiResult[ModelOptions]:
    model = configured_model()
    options = await list_chat_models(model) or (ModelOption(model.model, model.model),)
    return ApiResult(data=ModelOptions(default_model=preferred_model(model, options), models=list(options)))


async def model_for_turn(body: ChatRequest, _user: CurrentUser) -> ChatModel:
    model = configured_model()
    if body.model == model.model:
        return model
    options = await list_chat_models(model)
    if body.model is None:
        return replace(model, model=preferred_model(model, options))
    if not options:
        raise HTTPException(503, "暂时无法读取网关模型列表，请使用默认模型或刷新页面。")
    if not any(option.id == body.model for option in options):
        raise HTTPException(400, "所选模型已不可用，请刷新页面后重新选择。")
    return replace(model, model=body.model)


def prepare_turn(body: ChatRequest, user: AuthenticatedUser, db: DbSession, model: ChatModel) -> Turn:
    session = owned_session(db, body.session_id, user.id, lock=True)
    now = db.scalar(select(func.now(3)))
    stale_before = now - timedelta(seconds=GENERATION_TIMEOUT_SECONDS + 30)
    running = db.scalars(
        select(AiRun)
        .where(AiRun.session_id == session.id, AiRun.status == "running", AiRun.is_deleted == 0)
        .with_for_update()
    ).all()
    if any(run.started_at >= stale_before for run in running):
        raise HTTPException(409, "此会话正在生成回复，请等本轮结束后再发送。")
    for run in running:
        run.status = "interrupted"
        run.finished_at = now

    history = recent_history(db, session.id)
    history.append({"role": "user", "content": body.query})
    question = AiMessage(session_id=session.id, role="user", content=body.query, created_by=user.id)
    assistant = AiMessage(session_id=session.id, role="assistant", content="", created_by=user.id)
    db.add_all([question, assistant])
    db.flush()
    run = AiRun(
        session_id=session.id,
        user_message_id=question.id,
        assistant_message_id=assistant.id,
        created_by=user.id,
    )
    db.add(run)
    session.updated_at = now
    db.commit()

    return Turn(model, user, session.id, run.id, question.id, assistant.id, history)


async def start_turn(
    body: ChatRequest,
    user: CurrentUser,
    db: DbSession,
    model: Annotated[ChatModel, Depends(model_for_turn)],
) -> AsyncIterator[Turn]:
    turn = await run_in_threadpool(prepare_turn, body, user, db, model)
    try:
        yield turn
    finally:
        with CancelScope(shield=True):
            if turn.producer is not None and not turn.producer.done():
                turn.producer.cancel()
                await asyncio.gather(turn.producer, return_exceptions=True)
            await turn.timeline.finish_pending_tools()
            if not turn.saved:
                try:
                    await run_in_threadpool(save_turn, turn, "interrupted", elapsed_ms(turn.started))
                except SQLAlchemyError as exc:
                    logger.error("断流后保存会话失败 (%s)", type(exc).__name__)


async def produce_turn(turn: Turn) -> str | None:
    scope = current_timeline.set(turn.timeline)
    request = AgentRequest(turn.user, turn.session_id, turn.run_id)
    try:
        async with asyncio.timeout(GENERATION_TIMEOUT_SECONDS), aclosing(answer(turn.model, request, turn.history)) as tokens:
            async for token in tokens:
                turn.pieces.append(token)
                turn.timeline.queue.put_nowait(("token", {"content": token}))
        if not any(piece.strip() for piece in turn.pieces):
            raise ModelError("模型没有生成可显示的回复。")
    except ModelError as exc:
        return str(exc)
    except TimeoutError:
        return "模型生成时间过长，请缩短问题后重试。"
    except Exception as exc:
        logger.warning("Agent 调用异常 (%s)", type(exc).__name__)
        return "模型请求未完成，请重试。"
    finally:
        try:
            await turn.timeline.finish_pending_tools()
        finally:
            current_timeline.reset(scope)
            turn.timeline.queue.put_nowait(None)
    return None


@router.post("/stream", response_class=EventSourceResponse)
async def chat_stream(turn: Annotated[Turn, Depends(start_turn)]) -> AsyncIterator[ServerSentEvent]:
    yield ServerSentEvent(event="start", data={
        "user_message_id": turn.user_message_id,
        "message_id": turn.assistant_message_id,
    })
    turn.producer = asyncio.create_task(produce_turn(turn))
    while (item := await turn.timeline.queue.get()) is not None:
        event, data = item
        yield ServerSentEvent(event=event, data=data)
    error = await turn.producer
    status = "error" if error else "success"
    duration_ms = elapsed_ms(turn.started)
    try:
        with CancelScope(shield=True):
            saved = await run_in_threadpool(save_turn, turn, status, duration_ms, error)
            turn.saved = True
        if not saved:
            error = "会话已删除或本轮已结束，无法保存回复。"
    except SQLAlchemyError as exc:
        logger.error("保存会话失败 (%s)", type(exc).__name__)
        error = "数据库暂时无法保存本轮回复，请稍后重试。"
    if error:
        yield ServerSentEvent(event="error", data={"message": error})
    else:
        yield ServerSentEvent(event="done", data=turn.stats(duration_ms))
