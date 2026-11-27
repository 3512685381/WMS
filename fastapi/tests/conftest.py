import sys
from pathlib import Path
from uuid import uuid4

import pytest
from sqlalchemy.orm import Session

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.database import engine  # noqa: E402
from app.core.security import load_active_user  # noqa: E402
from app.models.memory import AiMessage, AiRun, AiSession  # noqa: E402


@pytest.fixture
def db():
    """单连接事务，测试结束整体回滚，不污染演示数据。"""
    connection = engine.connect()
    transaction = connection.begin()
    session = Session(bind=connection, expire_on_commit=False)
    try:
        yield session
    finally:
        session.close()
        transaction.rollback()
        connection.close()


@pytest.fixture
def admin(db):
    return load_active_user(db, 1, str(uuid4()), 4102444800)


@pytest.fixture
def limited_user(db):
    return load_active_user(db, 3, str(uuid4()), 4102444800)


@pytest.fixture
def turn(db):
    """一组会话 + 消息 + 运行记录，草稿回执受影响于外键约束。"""
    session = AiSession(id=str(uuid4()), user_id=1, created_by=1, title="测试会话")
    db.add(session)
    db.flush()
    question = AiMessage(session_id=session.id, role="user", content="测试提问", created_by=1)
    answer = AiMessage(session_id=session.id, role="assistant", content="", created_by=1)
    db.add_all([question, answer])
    db.flush()
    run = AiRun(
        session_id=session.id,
        user_message_id=question.id,
        assistant_message_id=answer.id,
        operation_id=str(uuid4()),
        created_by=1,
    )
    db.add(run)
    db.flush()
    return session, run
