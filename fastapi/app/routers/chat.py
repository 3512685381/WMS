import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import aclosing
from dataclasses import dataclass, field, replace
from datetime import timedelta
from time import monotonic
from typing import Annotated
from uuid import NAMESPACE_URL, UUID, uuid4, uuid5

from anyio import CancelScope
from fastapi import APIRouter, Depends, HTTPException
from fastapi.concurrency import run_in_threadpool
from fastapi.sse import EventSourceResponse, ServerSentEvent
from pydantic import BaseModel, StringConstraints
from sqlalchemy import func, select
from sqlalchemy.orm import Session
from sqlalchemy.exc import SQLAlchemyError

from app.agent.agents.main_agent import answer
from app.agent.events import Timeline, current_timeline, elapsed_ms
from app.agent.journal import RunJournal
from app.agent.llm import GENERATION_TIMEOUT_SECONDS, ChatModel, ModelError, ModelOption, list_chat_models
from app.agent.memory import load_conversation, refresh_summary, sync_pending_operation
from app.agent.policy import TurnPolicy, build_policy
from app.agent.registry import AgentRequest
from app.agent.run_state import RUN_LEASE_SECONDS
from app.core.database import DbSession, SessionLocal
from app.core.security import AuthenticatedUser, CurrentUser
from app.models.memory import AiMessage, AiRun, AiSession
from app.routers.session import draft_cards, owned_session
from app.schemas import ApiResult, DraftView

router = APIRouter(prefix="/ai/chat", tags=["AI 对话"])
logger = logging.getLogger(__name__)
JOURNAL_INTERVAL_SECONDS = 1.0
RequestId = Annotated[
    str, StringConstraints(strip_whitespace=True, min_length=8, max_length=64, pattern=r"[A-Za-z0-9_.:-]+"),
]


class ChatRequest(BaseModel):
    session_id: UUID
    query: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=4000)]
    model: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=256)] | None = None
    client_request_id: RequestId | None = None


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
    policy: TurnPolicy
    context_text: str
    summary: str | None
    journal: RunJournal
    started: float = field(default_factory=monotonic)
    timeline: Timeline = field(default_factory=Timeline)
    producer: asyncio.Task[str | None] | None = None
    saved: bool = False

    def stats(self, duration_ms: int) -> dict:
        return {
            "message_id": self.assistant_message_id,
            "run_id": self.run_id,
            "expert_count": len(self.timeline.experts),
            "tool_call_count": self.timeline.tool_calls,
            "total_duration_ms": duration_ms,
        }


@dataclass(frozen=True, slots=True)
class Replay:
    """同一 client_request_id 的重试不重新执行，直接回放已保存的结果。"""

    run_id: int
    user_message_id: int
    assistant_message_id: int
    content: str
    status: str
    expert_count: int
    tool_call_count: int
    total_duration_ms: int
    drafts: list[DraftView]


def operation_id_for(user_id: int, session_id: str, client_request_id: str | None) -> str:
    """请求标识存在时派生稳定 operation_id，网络重试沿用同一草稿幂等键。"""
    if client_request_id:
        return str(uuid5(NAMESPACE_URL, f"wms:ai:{user_id}:{session_id}:{client_request_id}"))
    return str(uuid4())


def save_turn(turn: Turn, status: str, duration_ms: int, error: str | None = None) -> bool:
    turn.journal.flush(final=True)
    with SessionLocal.begin() as db:
        session = db.get(AiSession, turn.session_id, with_for_update=True)
        run = db.get(AiRun, turn.run_id, with_for_update=True)
        if session is None or session.is_deleted or run is None or run.is_deleted or run.status != "running":
            return False

        assistant = db.get(AiMessage, turn.assistant_message_id)
        if assistant is None:
            return False
        assistant.content = turn.journal.content
        assistant.expert_count = len(turn.timeline.experts)
        assistant.tool_call_count = turn.timeline.tool_calls
        assistant.total_duration_ms = duration_ms
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


def existing_replay(db: Session, session: AiSession, client_request_id: str) -> Replay | None:
    run = db.scalar(
        select(AiRun)
        .where(
            AiRun.session_id == session.id,
            AiRun.client_request_id == client_request_id,
            AiRun.is_deleted == 0,
        )
        .with_for_update()
    )
    if run is None:
        return None
    if run.status == "running":
        raise HTTPException(409, "同一请求正在处理中，请稍后读取本轮结果。")
    message = db.get(AiMessage, run.assistant_message_id)
    if message is None:
        return None
    drafts = draft_cards(db, [run.id]).get(run.id, [])
    return Replay(
        run_id=run.id,
        user_message_id=run.user_message_id,
        assistant_message_id=run.assistant_message_id,
        content=message.content,
        status=run.status,
        expert_count=message.expert_count,
        tool_call_count=message.tool_call_count,
        total_duration_ms=message.total_duration_ms,
        drafts=drafts,
    )


def prepare_turn(body: ChatRequest, user: AuthenticatedUser, db: DbSession, model: ChatModel) -> Turn | Replay:
    session = owned_session(db, body.session_id, user.id, lock=True)
    if body.client_request_id:
        replay = existing_replay(db, session, body.client_request_id)
        if replay is not None:
            return replay

    now = db.scalar(select(func.now(3)))
    lease_deadline = now - timedelta(seconds=RUN_LEASE_SECONDS)
    running = db.scalars(
        select(AiRun)
        .where(AiRun.session_id == session.id, AiRun.status == "running", AiRun.is_deleted == 0)
        .with_for_update()
    ).all()
    if any(run.heartbeat_at >= lease_deadline for run in running):
        raise HTTPException(409, "此会话正在生成回复，请等本轮结束后再发送。")
    for run in running:
        run.status = "interrupted"
        run.finished_at = now

    conversation = load_conversation(db, session)
    operation_id = operation_id_for(user.id, session.id, body.client_request_id)
    policy = build_policy(
        db, user, body.query,
        operation_id=operation_id,
        client_request_id=body.client_request_id,
        context=conversation.context,
    )
    sync_pending_operation(db, session, policy)

    history = [*conversation.messages, {"role": "user", "content": body.query}]
    question = AiMessage(session_id=session.id, role="user", content=body.query, created_by=user.id)
    assistant = AiMessage(session_id=session.id, role="assistant", content="", created_by=user.id)
    db.add_all([question, assistant])
    db.flush()
    run = AiRun(
        session_id=session.id,
        user_message_id=question.id,
        assistant_message_id=assistant.id,
        client_request_id=body.client_request_id,
        operation_id=operation_id,
        heartbeat_at=now,
        created_by=user.id,
    )
    db.add(run)
    session.updated_at = now
    db.commit()

    journal = RunJournal(run.id, assistant.id, user.id)
    return Turn(
        model, user, session.id, run.id, question.id, assistant.id, history,
        policy, conversation.context_text, conversation.summary, journal,
    )


async def start_turn(
    body: ChatRequest,
    user: CurrentUser,
    db: DbSession,
    model: Annotated[ChatModel, Depends(model_for_turn)],
) -> AsyncIterator[Turn | Replay]:
    prepared = await run_in_threadpool(prepare_turn, body, user, db, model)
    if isinstance(prepared, Replay):
        yield prepared
        return
    turn = prepared
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
    request = AgentRequest(turn.user, turn.session_id, turn.run_id, turn.policy)
    try:
        async with asyncio.timeout(GENERATION_TIMEOUT_SECONDS), aclosing(answer(
            turn.model, request, turn.history,
            context_text=turn.context_text, summary=turn.summary,
        )) as tokens:
            async for token in tokens:
                turn.journal.append_text(token)
                turn.timeline.queue.put_nowait(("token", {"content": token}))
        if not turn.journal.content.strip():
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


async def journal_loop(turn: Turn) -> None:
    """周期性落库：步骤、回复文本和心跳，进程崩溃后最多丢失一个刷新周期。"""
    while True:
        await asyncio.sleep(JOURNAL_INTERVAL_SECONDS)
        try:
            await run_in_threadpool(turn.journal.flush)
        except Exception as exc:  # noqa: BLE001 - 刷新失败不能中断本轮回复
            logger.warning("运行日志刷新异常 (%s)", type(exc).__name__)


def load_draft_cards(run_id: int) -> list[DraftView]:
    with SessionLocal() as db:
        return draft_cards(db, [run_id]).get(run_id, [])


def schedule_summary(turn: Turn) -> None:
    async def refresh() -> None:
        await refresh_summary(turn.model, turn.session_id)

    task = asyncio.create_task(refresh())
    background = _BACKGROUND_TASKS
    background.add(task)
    task.add_done_callback(background.discard)


_BACKGROUND_TASKS: set[asyncio.Task] = set()


@router.post("/stream", response_class=EventSourceResponse)
async def chat_stream(prepared: Annotated[Turn | Replay, Depends(start_turn)]) -> AsyncIterator[ServerSentEvent]:
    if isinstance(prepared, Replay):
        yield ServerSentEvent(event="start", data={
            "user_message_id": prepared.user_message_id,
            "message_id": prepared.assistant_message_id,
            "run_id": prepared.run_id,
            "replayed": True,
            "status": prepared.status,
        })
        if prepared.content:
            yield ServerSentEvent(event="token", data={"content": prepared.content})
        yield ServerSentEvent(event="done", data={
            "message_id": prepared.assistant_message_id,
            "run_id": prepared.run_id,
            "expert_count": prepared.expert_count,
            "tool_call_count": prepared.tool_call_count,
            "total_duration_ms": prepared.total_duration_ms,
            "replayed": True,
            "drafts": [draft.model_dump(mode="json") for draft in prepared.drafts],
        })
        return

    turn = prepared
    turn.timeline.bind()
    turn.timeline.on_step = turn.journal.record_step
    yield ServerSentEvent(event="start", data={
        "user_message_id": turn.user_message_id,
        "message_id": turn.assistant_message_id,
        "run_id": turn.run_id,
        "replayed": False,
        "operation": turn.policy.operation.value,
        "write_state": turn.policy.state,
        "missing_fields": list(turn.policy.missing_fields),
    })
    flusher = asyncio.create_task(journal_loop(turn))
    turn.producer = asyncio.create_task(produce_turn(turn))
    error: str | None = None
    try:
        while (item := await turn.timeline.queue.get()) is not None:
            event, data = item
            yield ServerSentEvent(event=event, data=data)
        error = await turn.producer
        flusher.cancel()
        await asyncio.gather(flusher, return_exceptions=True)

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
    finally:
        if not flusher.done():
            flusher.cancel()
            await asyncio.gather(flusher, return_exceptions=True)

    drafts = await run_in_threadpool(load_draft_cards, turn.run_id)
    if error:
        yield ServerSentEvent(event="error", data={"message": error})
        return
    yield ServerSentEvent(event="done", data={
        **turn.stats(duration_ms),
        "replayed": False,
        "drafts": [draft.model_dump(mode="json") for draft in drafts],
    })
    schedule_summary(turn)
