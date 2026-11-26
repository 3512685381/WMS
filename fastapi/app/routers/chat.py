import asyncio
import logging
from collections.abc import AsyncIterator, Iterator
from contextlib import aclosing
from dataclasses import dataclass, field, replace
from datetime import timedelta
from time import monotonic
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from fastapi.concurrency import run_in_threadpool
from fastapi.sse import EventSourceResponse, ServerSentEvent
from pydantic import BaseModel, StringConstraints
from sqlalchemy import func, select

from app.agent.llm import GENERATION_TIMEOUT_SECONDS, ChatModel, ModelError, ModelOption, list_chat_models
from app.core.database import DbSession, SessionLocal
from app.core.security import CurrentUser
from app.models.memory import AiMessage, AiRun, AiSession
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
    session_id: str
    run_id: int
    user_message_id: int
    assistant_message_id: int
    history: list[dict[str, str]]
    started: float = field(default_factory=monotonic)
    pieces: list[str] = field(default_factory=list)
    saved: bool = False

    def elapsed_ms(self) -> int:
        return round((monotonic() - self.started) * 1000)


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


def save_turn(turn: Turn, status: str, error: str | None = None) -> bool:
    with SessionLocal.begin() as db:
        session = db.get(AiSession, turn.session_id, with_for_update=True)
        run = db.get(AiRun, turn.run_id, with_for_update=True)
        if session is None or run is None or run.status != "running":
            return False

        assistant = db.get(AiMessage, turn.assistant_message_id)
        assistant.content = "".join(turn.pieces)
        assistant.total_duration_ms = turn.elapsed_ms()
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


@router.get("/models")
async def model_options(_user: CurrentUser) -> ApiResult[ModelOptions]:
    model = configured_model()
    options = await list_chat_models(model) or (ModelOption(model.model, model.model),)
    preferred = next((option.id for option in options if option.id == model.model), options[0].id)
    return ApiResult(data=ModelOptions(default_model=preferred, models=list(options)))


async def model_for_turn(body: ChatRequest, _user: CurrentUser) -> ChatModel:
    model = configured_model()
    if body.model is None or body.model == model.model:
        return model
    options = await list_chat_models(model)
    if not options:
        raise HTTPException(503, "暂时无法读取网关模型列表，请使用默认模型或刷新页面。")
    if not any(option.id == body.model for option in options):
        raise HTTPException(400, "所选模型已不可用，请刷新页面后重新选择。")
    return replace(model, model=body.model)


def start_turn(
    body: ChatRequest,
    user: CurrentUser,
    db: DbSession,
    model: Annotated[ChatModel, Depends(model_for_turn)],
) -> Iterator[Turn]:

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
    answer = AiMessage(session_id=session.id, role="assistant", content="", created_by=user.id)
    db.add_all([question, answer])
    db.flush()
    run = AiRun(
        session_id=session.id,
        user_message_id=question.id,
        assistant_message_id=answer.id,
        created_by=user.id,
    )
    db.add(run)
    session.updated_at = now
    db.commit()

    turn = Turn(model, session.id, run.id, question.id, answer.id, history)
    try:
        yield turn
    finally:
        if not turn.saved:
            save_turn(turn, "interrupted")


@router.post("/stream", response_class=EventSourceResponse)
async def chat_stream(turn: Annotated[Turn, Depends(start_turn)]) -> AsyncIterator[ServerSentEvent]:
    yield ServerSentEvent(event="start", data={
        "user_message_id": turn.user_message_id,
        "message_id": turn.assistant_message_id,
    })
    error = None
    try:
        async with asyncio.timeout(GENERATION_TIMEOUT_SECONDS), aclosing(turn.model.stream(turn.history)) as tokens:
            async for token in tokens:
                turn.pieces.append(token)
                yield ServerSentEvent(event="token", data={"content": token})
        if not any(piece.strip() for piece in turn.pieces):
            raise ModelError("模型没有生成可显示的回复。")
    except ModelError as exc:
        error = str(exc)
    except TimeoutError:
        error = "模型生成时间过长，请缩短问题后重试。"
    except Exception as exc:
        logger.warning("模型调用异常 (%s)", type(exc).__name__)
        error = "模型请求未完成，请重试。"

    status = "error" if error else "success"
    saved = await run_in_threadpool(save_turn, turn, status, error)
    turn.saved = True
    if not saved:
        error = "会话已删除或本轮已结束，无法保存回复。"
    if error:
        yield ServerSentEvent(event="error", data={"message": error})
    else:
        yield ServerSentEvent(event="done", data={
            "message_id": turn.assistant_message_id,
            "expert_count": 0,
            "tool_call_count": 0,
            "total_duration_ms": turn.elapsed_ms(),
        })
