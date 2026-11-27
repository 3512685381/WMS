import asyncio
from contextvars import ContextVar
from dataclasses import dataclass, field
from datetime import datetime
from time import monotonic

from pydantic import JsonValue

from app.schemas import SHANGHAI


@dataclass(frozen=True, slots=True)
class ExecutionStep:
    step_order: int
    event_type: str
    agent_name: str
    tool_name: str | None
    tool_args: JsonValue | None
    result_summary: str | None
    duration_ms: int | None
    status: str
    created_at: datetime

    def event_data(self) -> dict:
        data = {
            "id": f"{self.step_order}:{self.event_type}",
            "step_order": self.step_order,
            "event_type": self.event_type,
            "agent_name": self.agent_name,
            "tool_name": self.tool_name,
            "tool_args": self.tool_args,
            "result_summary": self.result_summary,
            "duration_ms": self.duration_ms,
            "status": self.status,
            "created_at": self.created_at.isoformat(timespec="milliseconds"),
        }
        if self.event_type == "dispatch":
            data["task"] = self.result_summary
        elif self.event_type == "tool_start":
            data["args"] = self.tool_args
        elif self.event_type == "tool_end":
            data["summary"] = self.result_summary
        return data


@dataclass(slots=True)
class Timeline:
    queue: asyncio.Queue[tuple[str, dict] | None] = field(default_factory=asyncio.Queue)
    steps: list[ExecutionStep] = field(default_factory=list)
    experts: set[str] = field(default_factory=set)
    expert_calls: int = 0
    tool_calls: int = 0
    tool_slots: asyncio.Semaphore = field(default_factory=lambda: asyncio.Semaphore(4))
    pending_tools: set[asyncio.Task] = field(default_factory=set)
    evidence: list[dict] = field(default_factory=list)
    _step_order: int = 0

    def record(
        self,
        event_type: str,
        agent_name: str,
        *,
        step_order: int | None = None,
        tool_name: str | None = None,
        tool_args: JsonValue | None = None,
        summary: str | None = None,
        duration_ms: int | None = None,
        status: str = "success",
    ) -> int:
        if step_order is None:
            self._step_order += 1
            step_order = self._step_order
        index = next((
            index for index, previous in enumerate(self.steps)
            if previous.step_order == step_order and previous.event_type == event_type
        ), len(self.steps))
        created_at = self.steps[index].created_at if index < len(self.steps) else datetime.now(SHANGHAI)
        step = ExecutionStep(
            step_order, event_type, agent_name, tool_name, tool_args, summary,
            duration_ms, status, created_at,
        )
        if index == len(self.steps):
            self.steps.append(step)
        else:
            self.steps[index] = step
        self.queue.put_nowait((event_type, step.event_data()))
        return step_order

    async def finish_pending_tools(self) -> None:
        if self.pending_tools:
            await asyncio.gather(*(asyncio.shield(task) for task in self.pending_tools), return_exceptions=True)


current_timeline: ContextVar[Timeline] = ContextVar("wms_timeline")


def elapsed_ms(started: float) -> int:
    return round((monotonic() - started) * 1000)
