from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
import logging

from fastapi import FastAPI, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.agent.run_state import sweep_stale_runs
from app.core.config import settings
from app.core.database import DbSession, SessionLocal, engine
from app.routers.chat import router as chat_router
from app.routers.run import router as run_router
from app.routers.session import router as sessions_router
from app.schemas import ApiResult

logger = logging.getLogger(__name__)


def sweep_runs_on_startup() -> None:
    """进程重启后不再永久显示执行中，把失去执行者的运行标记为 interrupted。"""
    try:
        with SessionLocal.begin() as db:
            sweep_stale_runs(db)
    except SQLAlchemyError as error:
        logger.warning("启动时收敛运行记录失败 (%s)", type(error).__name__)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    try:
        await run_in_threadpool(sweep_runs_on_startup)
        yield
    finally:
        engine.dispose()


app = FastAPI(title="WMS AI 后端", version="0.2.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_methods=["GET", "POST", "PUT", "DELETE"],
    allow_headers=["Authorization", "Content-Type"],
)
app.include_router(sessions_router)
app.include_router(chat_router)
app.include_router(run_router)


@app.exception_handler(StarletteHTTPException)
async def http_error(_: Request, error: StarletteHTTPException) -> JSONResponse:
    message = error.detail if isinstance(error.detail, str) else "请求无法处理"
    result = ApiResult(code=error.status_code, msg=message, data=None)
    return JSONResponse(result.model_dump(), status_code=error.status_code, headers=error.headers)


@app.exception_handler(RequestValidationError)
async def validation_error(_: Request, __: RequestValidationError) -> JSONResponse:
    result = ApiResult(code=400, msg="请求参数或请求体有误", data=None)
    return JSONResponse(result.model_dump(), status_code=400)


@app.exception_handler(Exception)
async def unexpected_error(_: Request, error: Exception) -> JSONResponse:
    logger.error("请求处理失败 (%s)", type(error).__name__)
    result = ApiResult(code=500, msg="服务器处理请求失败", data=None)
    return JSONResponse(result.model_dump(), status_code=500)


@app.get("/ai/health", tags=["运行状态"])
def health(db: DbSession) -> ApiResult[dict[str, str]]:
    try:
        db.execute(text("SELECT 1"))
    except SQLAlchemyError:
        raise HTTPException(503, "数据库连接不可用") from None
    return ApiResult(data={"service": "fastapi", "database": "up"})
