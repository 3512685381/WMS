from datetime import datetime

from pydantic import JsonValue
from sqlalchemy import BigInteger, FetchedValue, String, Text, text
from sqlalchemy.dialects.mysql import CHAR, DATETIME, INTEGER, JSON, LONGTEXT, TINYINT
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class AuditColumns:
    created_at: Mapped[datetime] = mapped_column(DATETIME(fsp=3), server_default=text("CURRENT_TIMESTAMP(3)"))
    updated_at: Mapped[datetime] = mapped_column(
        DATETIME(fsp=3), server_default=text("CURRENT_TIMESTAMP(3)"), server_onupdate=FetchedValue()
    )
    created_by: Mapped[int | None] = mapped_column(BigInteger)
    is_deleted: Mapped[int] = mapped_column(TINYINT(unsigned=True), server_default=text("0"))


class AiSession(AuditColumns, Base):
    __tablename__ = "ai_session"

    id: Mapped[str] = mapped_column(CHAR(36, charset="ascii", collation="ascii_bin"), primary_key=True)
    user_id: Mapped[int] = mapped_column(BigInteger)
    title: Mapped[str] = mapped_column(String(160), server_default=text("'新对话'"))
    context_summary: Mapped[str | None] = mapped_column(Text)
    context_summary_through_message_id: Mapped[int | None] = mapped_column(BigInteger)
    context_state: Mapped[dict | None] = mapped_column(JSON)


class AiMessage(AuditColumns, Base):
    __tablename__ = "ai_message"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    session_id: Mapped[str] = mapped_column(CHAR(36, charset="ascii", collation="ascii_bin"))
    role: Mapped[str] = mapped_column(String(9))
    content: Mapped[str] = mapped_column(LONGTEXT)
    expert_count: Mapped[int] = mapped_column(INTEGER(unsigned=True), server_default=text("0"))
    tool_call_count: Mapped[int] = mapped_column(INTEGER(unsigned=True), server_default=text("0"))
    total_duration_ms: Mapped[int] = mapped_column(INTEGER(unsigned=True), server_default=text("0"))


class AiRun(AuditColumns, Base):
    __tablename__ = "ai_run"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    session_id: Mapped[str] = mapped_column(CHAR(36, charset="ascii", collation="ascii_bin"))
    user_message_id: Mapped[int] = mapped_column(BigInteger)
    assistant_message_id: Mapped[int] = mapped_column(BigInteger)
    client_request_id: Mapped[str | None] = mapped_column(String(64))
    operation_id: Mapped[str | None] = mapped_column(String(36))
    status: Mapped[str] = mapped_column(String(12), server_default=text("'running'"))
    started_at: Mapped[datetime] = mapped_column(DATETIME(fsp=3), server_default=text("CURRENT_TIMESTAMP(3)"))
    heartbeat_at: Mapped[datetime] = mapped_column(DATETIME(fsp=3), server_default=text("CURRENT_TIMESTAMP(3)"))
    finished_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=3))
    error_message: Mapped[str | None] = mapped_column(String(500))


class AiExecutionStep(AuditColumns, Base):
    __tablename__ = "ai_execution_step"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    message_id: Mapped[int] = mapped_column(BigInteger)
    step_order: Mapped[int] = mapped_column(INTEGER(unsigned=True))
    event_type: Mapped[str] = mapped_column(String(16))
    agent_name: Mapped[str | None] = mapped_column(String(64))
    call_id: Mapped[str | None] = mapped_column(String(64))
    parent_call_id: Mapped[str | None] = mapped_column(String(64))
    tool_name: Mapped[str | None] = mapped_column(String(96))
    tool_args: Mapped[JsonValue | None] = mapped_column(JSON)
    result_summary: Mapped[str | None] = mapped_column(Text)
    duration_ms: Mapped[int | None] = mapped_column(INTEGER(unsigned=True))
    status: Mapped[str] = mapped_column(String(8), server_default=text("'success'"))


class AiDraftOperation(AuditColumns, Base):
    __tablename__ = "ai_draft_operation"

    id: Mapped[str] = mapped_column(CHAR(36, charset="ascii", collation="ascii_bin"), primary_key=True)
    operation_id: Mapped[str] = mapped_column(CHAR(36, charset="ascii", collation="ascii_bin"))
    operation_key: Mapped[str] = mapped_column(String(191))
    session_id: Mapped[str] = mapped_column(CHAR(36, charset="ascii", collation="ascii_bin"))
    run_id: Mapped[int] = mapped_column(BigInteger)
    user_id: Mapped[int] = mapped_column(BigInteger)
    order_type: Mapped[str] = mapped_column(String(16))
    order_id: Mapped[int] = mapped_column(BigInteger)
    order_no: Mapped[str] = mapped_column(String(14))
    item_count: Mapped[int] = mapped_column(INTEGER(unsigned=True), server_default=text("0"))
    status: Mapped[str] = mapped_column(String(12), server_default=text("'created'"))
