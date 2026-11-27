"""运行租约：用心跳判断某一轮是否还有执行者，收敛进程崩溃遗留的 running 记录。"""

import logging

from sqlalchemy import func, select, text
from sqlalchemy.orm import Session

from app.models.memory import AiRun

logger = logging.getLogger(__name__)
RUN_LEASE_SECONDS = 60


def sweep_stale_runs(db: Session, session_id: str | None = None) -> int:
    """把心跳超时仍为 running 的运行标记为 interrupted，返回处理条数。"""
    seconds = int(RUN_LEASE_SECONDS)
    condition = text(
        "status = 'running' AND is_deleted = 0 AND heartbeat_at < CURRENT_TIMESTAMP(3) "
        f"- INTERVAL {seconds} SECOND"
    )
    query = select(AiRun).where(condition)
    if session_id is not None:
        query = query.where(AiRun.session_id == session_id)
    stale = db.scalars(query.with_for_update()).all()
    for run in stale:
        run.status = "interrupted"
        run.finished_at = func.now(3)
    if stale:
        logger.info("收敛 %s 条失去执行者的运行记录", len(stale))
    return len(stale)
