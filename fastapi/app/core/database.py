from collections.abc import Iterator
from typing import Annotated

from fastapi import Depends
from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.core.config import settings

engine = create_engine(
    settings.mysql_url(),
    pool_pre_ping=True,
    pool_recycle=1800,
    hide_parameters=True,
    connect_args={
        "connect_timeout": 5,
        "init_command": "SET time_zone = '+08:00'",
    },
)
SessionLocal = sessionmaker(engine, expire_on_commit=False)


def get_db() -> Iterator[Session]:
    with SessionLocal() as db:
        yield db


DbSession = Annotated[Session, Depends(get_db)]
