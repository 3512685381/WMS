import json
from datetime import timedelta
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select, text

from app.core.database import SessionLocal
from app.core.security import get_current_user, load_active_user
from app.main import app
from app.models.memory import AiMessage, AiRun, AiSession
from app.routers import chat as chat_module
from app.routers.chat import model_for_turn


@pytest.fixture
def users():
    with SessionLocal() as db:
        return {
            "admin": load_active_user(db, 1, str(uuid4()), 4102444800),
            "limited": load_active_user(db, 3, str(uuid4()), 4102444800),
        }


@pytest.fixture
def current(users):
    return {"user": users["admin"]}


@pytest.fixture
def client(current, monkeypatch):
    app.dependency_overrides[get_current_user] = lambda: current["user"]
    app.dependency_overrides[model_for_turn] = lambda: object()
    monkeypatch.setattr(chat_module, "schedule_summary", lambda turn: None)
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()


@pytest.fixture
def answers(monkeypatch):
    calls = []

    async def answer(model, request, history, *, context_text="", summary=None):
        calls.append({"request": request, "history": history, "context": context_text})
        yield "根据库存记录，"
        yield "中央仓 20W 充电器可用量 20 件。"

    monkeypatch.setattr(chat_module, "answer", answer)
    return calls


@pytest.fixture
def session_id(client):
    response = client.post("/ai/sessions", json={"title": "接口测试"})
    assert response.status_code == 200
    created = response.json()["data"]["session_id"]
    yield created
    with SessionLocal.begin() as db:
        session = db.get(AiSession, created)
        if session is not None:
            db.delete(session)


def parse_events(client, payload):
    events = []
    with client.stream("POST", "/ai/chat/stream", json=payload) as response:
        status_code = response.status_code
        if status_code != 200:
            return status_code, events
        name = None
        for line in response.iter_lines():
            if line.startswith("event:"):
                name = line[6:].strip()
            elif line.startswith("data:"):
                events.append((name, json.loads(line[5:].strip())))
    return status_code, events


def test_chat_stream_persists_reply_and_run(client, answers, current, session_id):
    status, events = parse_events(client, {
        "session_id": session_id,
        "query": "查询中央仓 20W 充电器库存",
        "client_request_id": "req-" + uuid4().hex,
    })
    assert status == 200
    names = [name for name, _ in events]
    assert names[0] == "start"
    assert "token" in names
    assert names[-1] == "done"
    done = events[-1][1]
    assert done["drafts"] == []
    assert done["replayed"] is False
    assert answers[0]["request"].policy.writes_allowed is False

    messages = client.get(f"/ai/sessions/{session_id}/messages").json()["data"]
    assistant = [message for message in messages if message["role"] == "assistant"][-1]
    assert "可用量 20 件" in assistant["content"]
    assert assistant["run_status"] == "success"
    assert assistant["run_id"] == done["run_id"]

    run = client.get(f"/ai/runs/{done['run_id']}").json()["data"]
    assert run["status"] == "success"
    assert run["content"] == assistant["content"]
    assert run["steps"] == []


def test_same_client_request_id_is_replayed_once(client, answers, session_id):
    request_id = "req-" + uuid4().hex
    payload = {"session_id": session_id, "query": "查询中央仓库存", "client_request_id": request_id}

    _, first = parse_events(client, payload)
    _, second = parse_events(client, payload)

    assert len(answers) == 1
    assert first[-1][1]["message_id"] == second[-1][1]["message_id"]
    assert second[0][1]["replayed"] is True
    assert second[-1][1]["replayed"] is True
    tokens = [data["content"] for name, data in second if name == "token"]
    assert "".join(tokens) == "根据库存记录，中央仓 20W 充电器可用量 20 件。"

    with SessionLocal() as db:
        total = db.scalar(select(func.count()).select_from(AiRun).where(AiRun.session_id == session_id))
    assert total == 1


def test_explicit_draft_request_reaches_ready_policy(client, answers, session_id):
    status, events = parse_events(client, {
        "session_id": session_id,
        "query": "帮我在中央仓创建出库草稿，20W 充电器 5 件，批次 B001",
    })
    assert status == 200
    policy = answers[0]["request"].policy
    assert policy.operation.value == "create_outbound_draft"
    assert policy.state == "ready"
    assert policy.writes_allowed is True
    assert events[0][1]["write_state"] == "ready"


def test_analysis_request_does_not_authorize_write(client, answers, session_id):
    parse_events(client, {"session_id": session_id, "query": "分析一下中央仓补货建议"})
    policy = answers[0]["request"].policy
    assert policy.operation.value == "analysis"
    assert policy.writes_allowed is False


def test_busy_session_returns_conflict(client, answers, session_id):
    with SessionLocal.begin() as db:
        message = db.scalar(select(AiMessage).where(AiMessage.session_id == session_id))
        if message is None:
            message = AiMessage(session_id=session_id, role="user", content="占用", created_by=1)
            assistant = AiMessage(session_id=session_id, role="assistant", content="", created_by=1)
            db.add_all([message, assistant])
            db.flush()
        else:
            assistant = AiMessage(session_id=session_id, role="assistant", content="", created_by=1)
            db.add(assistant)
            db.flush()
        db.add(AiRun(
            session_id=session_id, user_message_id=message.id, assistant_message_id=assistant.id,
            status="running", heartbeat_at=func.now(3), created_by=1,
        ))

    response = client.post("/ai/chat/stream", json={"session_id": session_id, "query": "查询库存"})
    assert response.status_code == 409
    assert answers == []


def test_stale_run_is_reclaimed(client, answers, session_id):
    with SessionLocal.begin() as db:
        message = AiMessage(session_id=session_id, role="user", content="旧请求", created_by=1)
        assistant = AiMessage(session_id=session_id, role="assistant", content="", created_by=1)
        db.add_all([message, assistant])
        db.flush()
        run = AiRun(
            session_id=session_id, user_message_id=message.id, assistant_message_id=assistant.id,
            status="running", heartbeat_at=func.date_sub(func.now(3), text("INTERVAL 300 SECOND")), created_by=1,
        )
        db.add(run)
        db.flush()
        run_id = run.id

    status, _ = parse_events(client, {"session_id": session_id, "query": "查询库存"})
    assert status == 200
    with SessionLocal() as db:
        assert db.get(AiRun, run_id).status == "interrupted"


def test_run_cancel_requires_stale_lease(client, session_id):
    with SessionLocal.begin() as db:
        message = AiMessage(session_id=session_id, role="user", content="进行中", created_by=1)
        assistant = AiMessage(session_id=session_id, role="assistant", content="", created_by=1)
        db.add_all([message, assistant])
        db.flush()
        fresh = AiRun(session_id=session_id, user_message_id=message.id,
                      assistant_message_id=assistant.id, heartbeat_at=func.now(3), created_by=1)
        db.add(fresh)
        db.flush()
        fresh_id = fresh.id

    assert client.post(f"/ai/runs/{fresh_id}/cancel").status_code == 409

    with SessionLocal.begin() as db:
        run = db.get(AiRun, fresh_id)
        run.heartbeat_at = func.date_sub(func.now(3), text("INTERVAL 300 SECOND"))

    response = client.post(f"/ai/runs/{fresh_id}/cancel")
    assert response.status_code == 200
    assert response.json()["data"]["status"] == "interrupted"


def test_run_of_other_user_is_hidden(client, answers, current, users, session_id):
    _, events = parse_events(client, {"session_id": session_id, "query": "查询库存"})
    run_id = events[-1][1]["run_id"]

    current["user"] = users["limited"]
    assert client.get(f"/ai/runs/{run_id}").status_code == 404
    assert client.get(f"/ai/sessions/{session_id}/messages").status_code == 404


def test_messages_support_before_id_paging(client, session_id):
    for index in range(3):
        parse_events(client, {"session_id": session_id, "query": f"查询库存 {index}"})

    latest = client.get(f"/ai/sessions/{session_id}/messages", params={"limit": 2}).json()["data"]
    assert len(latest) == 2
    older = client.get(
        f"/ai/sessions/{session_id}/messages", params={"limit": 2, "before_id": latest[0]["id"]},
    ).json()["data"]
    assert len(older) == 2
    assert older[-1]["id"] < latest[0]["id"]
