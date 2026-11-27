from datetime import datetime
from typing import Annotated, Generic, Literal, TypeVar
from uuid import UUID
from zoneinfo import ZoneInfo

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, JsonValue, StringConstraints

T = TypeVar("T")
SHANGHAI = ZoneInfo("Asia/Shanghai")
SessionTitle = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=160)]
RunStatus = Literal["running", "success", "error", "interrupted"]


def shanghai_time(value: datetime) -> datetime:
    return value.replace(tzinfo=SHANGHAI) if value.tzinfo is None else value.astimezone(SHANGHAI)


ShanghaiDateTime = Annotated[datetime, AfterValidator(shanghai_time)]


class ApiResult(BaseModel, Generic[T]):
    code: int = 200
    msg: str = "success"
    data: T


class SessionCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: SessionTitle = "新对话"


class SessionRename(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: SessionTitle


class SessionView(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    session_id: UUID = Field(validation_alias="id")
    title: str
    created_at: ShanghaiDateTime
    updated_at: ShanghaiDateTime


class ExecutionStepView(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    step_order: int
    event_type: Literal["dispatch", "agent_start", "tool_start", "tool_end", "agent_end", "summarize"]
    agent_name: str | None
    call_id: str | None = None
    parent_call_id: str | None = None
    tool_name: str | None
    tool_args: JsonValue | None
    result_summary: str | None
    duration_ms: int | None
    status: Literal["running", "success", "error"]
    created_at: ShanghaiDateTime


class DraftView(BaseModel):
    order_id: int
    order_no: str
    order_type: Literal["inbound", "outbound"]
    status: int
    status_label: str
    source: str
    item_count: int = 0
    created_at: ShanghaiDateTime
    posted_at: ShanghaiDateTime | None = None
    reject_reason: str | None = None


class MessageView(BaseModel):
    id: int
    role: Literal["user", "assistant"]
    content: str
    expert_count: int
    tool_call_count: int
    total_duration_ms: int
    created_at: ShanghaiDateTime
    run_id: int | None = None
    run_status: RunStatus | None = None
    steps: list[ExecutionStepView] = Field(default_factory=list)
    drafts: list[DraftView] = Field(default_factory=list)


class RunView(BaseModel):
    run_id: int
    session_id: UUID
    message_id: int
    user_message_id: int
    status: RunStatus
    operation_id: str | None = None
    content: str
    error_message: str | None = None
    expert_count: int = 0
    tool_call_count: int = 0
    total_duration_ms: int = 0
    started_at: ShanghaiDateTime
    heartbeat_at: ShanghaiDateTime
    finished_at: ShanghaiDateTime | None = None
    steps: list[ExecutionStepView] = Field(default_factory=list)
    drafts: list[DraftView] = Field(default_factory=list)
