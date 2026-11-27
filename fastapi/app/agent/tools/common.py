from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import date
from typing import Annotated, Any

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.core.security import AuthenticatedUser

Search = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=128)]
EntityId = Annotated[int, Field(gt=0, le=2**63 - 1)]


class ToolArguments(BaseModel):
    model_config = ConfigDict(extra="forbid")


class PageArgs(ToolArguments):
    limit: Annotated[int, Field(ge=1, le=50)] = 20


class WarehouseArgs(PageArgs):
    warehouse: Search | None = Field(default=None, description="仓库名称或编码")


class ProductArgs(WarehouseArgs):
    keyword: Search | None = Field(default=None, description="商品名称或 SKU")


class DateArgs(ToolArguments):
    date_from: date | None = None
    date_to: date | None = None

    @model_validator(mode="after")
    def validate_dates(self):
        if self.date_from and self.date_to and self.date_from > self.date_to:
            raise ValueError("开始日期不得晚于结束日期")
        return self


@dataclass(frozen=True, slots=True)
class ToolContext:
    db: Session
    user: AuthenticatedUser
    session_id: str
    run_id: int


@dataclass(frozen=True, slots=True)
class ToolSpec:
    name: str
    description: str
    arguments: type[ToolArguments]
    permissions: tuple[str, ...]
    call: Callable[[ToolContext, Any], dict[str, Any]]
    writes_draft: bool = False

    def allowed(self, user: AuthenticatedUser) -> bool:
        return not user.permissions.isdisjoint(self.permissions)


class ToolError(Exception):
    def __init__(self, message: str, **details: Any):
        super().__init__(message)
        self.details = details


def match(value: str | None) -> str | None:
    if value is None:
        return None
    escaped = value.replace("!", "!!").replace("%", "!%").replace("_", "!_")
    return f"%{escaped}%"


def search_params(args: ProductArgs) -> dict[str, str | None]:
    return {"warehouse": match(args.warehouse), "keyword": match(args.keyword)}


def query_rows(db: Session, sql: str, params: Mapping[str, Any] | None = None) -> list[dict[str, Any]]:
    return [dict(row) for row in db.execute(text(sql), params).mappings()]


def paged(
    db: Session,
    sql: str,
    params: Mapping[str, Any],
    limit: int,
) -> dict[str, Any]:
    rows = query_rows(db, sql + "\nLIMIT :limit", {**params, "limit": limit + 1})
    return {"rows": rows[:limit], "truncated": len(rows) > limit}
