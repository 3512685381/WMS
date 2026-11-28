"""运行日志：把执行步骤、回复文本和运行心跳增量写入数据库。

先提交事务，成功后才更新"已写入"状态；提交失败时把步骤放回待写队列，下一次刷新会重写。
回复文本每次刷新都与上次落库的内容比对，内容有变化就写入，因此正常运行期间崩溃最多
丢失一个刷新周期的文本。最终刷新失败时运行保持未关闭状态，允许调用方再次尝试。
"""

import json
import logging
import threading

from dataclasses import replace

from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError

from app.agent.events import ExecutionStep
from app.core.database import SessionLocal

logger = logging.getLogger(__name__)
FINAL_FLUSH_ATTEMPTS = 2
STEP_UPSERT = text("""
    INSERT INTO ai_execution_step
        (message_id, step_order, event_type, agent_name, call_id, parent_call_id, tool_name, tool_args,
         result_summary, duration_ms, status, created_at, created_by)
    VALUES
        (:message_id, :step_order, :event_type, :agent_name, :call_id, :parent_call_id, :tool_name, :tool_args,
         :result_summary, :duration_ms, :status, :created_at, :created_by)
    ON DUPLICATE KEY UPDATE
        agent_name = VALUES(agent_name), call_id = VALUES(call_id), parent_call_id = VALUES(parent_call_id),
        tool_name = VALUES(tool_name), tool_args = VALUES(tool_args), result_summary = VALUES(result_summary),
        duration_ms = VALUES(duration_ms), status = VALUES(status), updated_at = CURRENT_TIMESTAMP(3)
    """)


class RunJournal:
    def __init__(self, run_id: int, message_id: int, user_id: int) -> None:
        self.run_id = run_id
        self.message_id = message_id
        self.user_id = user_id
        self._steps: list[ExecutionStep] = []
        self._pieces: list[str] = []
        self._flushed_content = ""
        self._closed = False
        self._lock = threading.Lock()
        # 周期任务与最终刷新可能并发，串行化数据库写入，避免旧快照覆盖新内容。
        self._flush_lock = threading.Lock()

    def record_step(self, step: ExecutionStep) -> None:
        # 数据库只接受 running/success/error，业务语义的细节放在步骤摘要里。
        if step.status not in ("running", "success", "error"):
            step = replace(step, status="success" if step.status in ("ok", "need_more") else "error")
        with self._lock:
            self._steps.append(step)

    def append_text(self, piece: str) -> None:
        with self._lock:
            self._pieces.append(piece)

    @property
    def content(self) -> str:
        with self._lock:
            return "".join(self._pieces)

    def flush(self, *, final: bool = False) -> bool:
        with self._flush_lock:
            attempts = FINAL_FLUSH_ATTEMPTS if final else 1
            for _ in range(attempts):
                if self._flush_once(final=final):
                    return True
            return False

    def _flush_once(self, *, final: bool) -> bool:
        with self._lock:
            if self._closed:
                return True
            steps, self._steps = self._steps, []
            content = "".join(self._pieces)
            write_text = content != self._flushed_content
        try:
            with SessionLocal.begin() as db:
                if steps:
                    db.execute(STEP_UPSERT, [{
                        "message_id": self.message_id,
                        "step_order": step.step_order,
                        "event_type": step.event_type,
                        "agent_name": step.agent_name,
                        "call_id": step.call_id,
                        "parent_call_id": step.parent_call_id,
                        "tool_name": step.tool_name,
                        "tool_args": None if step.tool_args is None else json.dumps(
                            step.tool_args, ensure_ascii=False, default=str,
                        ),
                        "result_summary": step.result_summary,
                        "duration_ms": step.duration_ms,
                        "status": step.status,
                        "created_at": step.created_at.replace(tzinfo=None),
                        "created_by": self.user_id,
                    } for step in steps])
                if write_text:
                    db.execute(
                        text("UPDATE ai_message SET content = :content WHERE id = :id AND is_deleted = 0"),
                        {"content": content, "id": self.message_id},
                    )
                db.execute(
                    text("UPDATE ai_run SET heartbeat_at = CURRENT_TIMESTAMP(3) "
                         "WHERE id = :id AND status = 'running'"),
                    {"id": self.run_id},
                )
        except SQLAlchemyError as error:
            logger.warning("运行日志写入失败 (%s): %s", type(error).__name__, str(error)[:300])
            with self._lock:
                self._steps[:0] = steps
            return False

        with self._lock:
            self._flushed_content = content
            if final:
                self._closed = True
        return True
