from datetime import datetime

from sqlalchemy.exc import SQLAlchemyError

import app.agent.journal as journal_module
from app.agent.events import ExecutionStep
from app.agent.journal import RunJournal


class FakeDB:
    def __init__(self, store):
        self.store = store

    def execute(self, statement, params):
        sql = str(statement)
        if "UPDATE ai_message" in sql:
            self.store["content"] = params["content"]
        elif "ai_run" in sql:
            self.store["heartbeats"] = self.store.get("heartbeats", 0) + 1
        else:
            self.store.setdefault("steps", []).extend(params)


class FakeTransaction:
    def __init__(self, store, failures):
        self.store = store
        self.failures = failures

    def __enter__(self):
        if self.failures["remaining"]:
            self.failures["remaining"] -= 1
            raise SQLAlchemyError("模拟数据库故障")
        return FakeDB(self.store)

    def __exit__(self, *_):
        return False


class FakeSessionLocal:
    def __init__(self, store, failures):
        self.store = store
        self.failures = failures

    def begin(self):
        return FakeTransaction(self.store, self.failures)


def step(order=1):
    return ExecutionStep(
        step_order=order, event_type="tool_end", agent_name="inventory_agent",
        tool_name="query_realtime_inventory", tool_args={"warehouse": "中央仓"},
        result_summary="命中 2 条库存记录", duration_ms=12, status="success",
        created_at=datetime(2026, 11, 28, 12, 0, 0),
    )


def install(monkeypatch, failures=0):
    store = {}
    state = {"remaining": failures}
    monkeypatch.setattr(journal_module, "SessionLocal", FakeSessionLocal(store, state))
    return store, state


def test_small_reply_is_written_each_tick(monkeypatch):
    store, _ = install(monkeypatch)
    journal = RunJournal(1, 2, 1)
    journal.append_text("短回复")
    assert journal.flush() is True
    # 不足 200 字也要落库，进程崩溃时不会整段丢失
    assert store["content"] == "短回复"
    assert store["heartbeats"] == 1
    journal.append_text("继续")
    assert journal.flush() is True
    assert store["content"] == "短回复继续"


def test_failed_flush_retries_steps_and_text(monkeypatch):
    store, state = install(monkeypatch)
    journal = RunJournal(1, 2, 1)
    journal.append_text("第一段")
    journal.record_step(step())

    state["remaining"] = 1
    assert journal.flush() is False
    assert "content" not in store
    assert "steps" not in store

    # 失败后步骤回到待写队列，重试会真正写入
    assert journal.flush() is True
    assert store["content"] == "第一段"
    assert len(store["steps"]) == 1


def test_final_flush_failure_keeps_journal_open(monkeypatch):
    store, state = install(monkeypatch)
    journal = RunJournal(1, 2, 1)
    journal.append_text("最终回复")
    journal.record_step(step())

    state["remaining"] = 2  # 最终刷新两次尝试都失败
    assert journal.flush(final=True) is False
    assert "content" not in store

    # 旧实现会在提交前标记已写入并关闭，导致后续重试直接成功但不落库
    assert journal.flush(final=True) is True
    assert store["content"] == "最终回复"
    assert len(store["steps"]) == 1
    assert store["heartbeats"] >= 1


def test_closed_journal_ignores_late_writes(monkeypatch):
    store, _ = install(monkeypatch)
    journal = RunJournal(1, 2, 1)
    journal.append_text("完成")
    assert journal.flush(final=True) is True
    journal.append_text("迟到")
    assert journal.flush() is True
    assert store["content"] == "完成"
