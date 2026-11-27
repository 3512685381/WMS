"""AI 多 Agent 可靠性改造的数据库迁移。

可重复执行：只补缺失的列、索引和表，不修改既有数据。
用法（在 fastapi 目录下）：
    .venv/bin/python migrations/m2026_11_27_ai_agent_contract.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from sqlalchemy import Engine, inspect, text  # noqa: E402

from app.core.database import engine  # noqa: E402

DRAFT_TABLE = """
CREATE TABLE IF NOT EXISTS ai_draft_operation (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '一轮运行的稳定操作标识',
    operation_key VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '草稿幂等键，与单据 request_key 一致',
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    order_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'inbound 或 outbound',
    order_id BIGINT NOT NULL,
    order_no CHAR(14) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_count INT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'created',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL,
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_draft_operation_key (operation_key),
    UNIQUE KEY uk_ai_draft_order (order_type, order_id),
    KEY idx_ai_draft_run (run_id),
    KEY idx_ai_draft_session (session_id, created_at),
    CONSTRAINT fk_ai_draft_run FOREIGN KEY (run_id) REFERENCES ai_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_draft_session FOREIGN KEY (session_id) REFERENCES ai_session (id) ON DELETE CASCADE,
    CONSTRAINT ck_ai_draft_order_type CHECK (order_type IN ('inbound', 'outbound')),
    CONSTRAINT ck_ai_draft_status CHECK (status IN ('created', 'failed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 草稿操作回执，断流后仍可定位单号';
"""

COLUMNS = (
    ("ai_session", "context_summary_through_message_id",
     "BIGINT NULL COMMENT '已压缩进摘要的最大消息 ID' AFTER context_summary"),
    ("ai_session", "context_state",
     "JSON NULL COMMENT '已确认的仓库、商品、批次与待补充字段' AFTER context_summary_through_message_id"),
    ("ai_run", "client_request_id",
     "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '客户端请求标识，用于请求级去重' "
     "AFTER assistant_message_id"),
    ("ai_run", "operation_id",
     "CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '本轮业务的稳定操作标识' AFTER client_request_id"),
    ("ai_run", "heartbeat_at",
     "DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '执行者心跳，用于租约判定' AFTER started_at"),
    ("ai_execution_step", "call_id",
     "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '一次专家或工具调用的标识' AFTER agent_name"),
    ("ai_execution_step", "parent_call_id",
     "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '上级专家调用标识' AFTER call_id"),
)

INDEXES = (
    ("ai_run", "uk_ai_run_client_request",
     "UNIQUE KEY uk_ai_run_client_request (session_id, client_request_id)"),
    ("ai_execution_step", "idx_ai_step_call", "KEY idx_ai_step_call (message_id, call_id)"),
)


def column_names(connection, table: str) -> set[str]:
    return {column["name"] for column in inspect(connection).get_columns(table)}


def index_names(connection, table: str) -> set[str]:
    return {index["name"] for index in inspect(connection).get_indexes(table)}


def migrate(target: Engine) -> list[str]:
    applied: list[str] = []
    with target.begin() as connection:
        for table, column, definition in COLUMNS:
            if column in column_names(connection, table):
                continue
            connection.execute(text(f"ALTER TABLE {table} ADD COLUMN {column} {definition}"))
            applied.append(f"{table}.{column}")

        for table, index, definition in INDEXES:
            if index in index_names(connection, table):
                continue
            connection.execute(text(f"ALTER TABLE {table} ADD {definition}"))
            applied.append(f"{table}.{index}")

        exists = inspect(connection).has_table("ai_draft_operation")
        if not exists:
            connection.execute(text(DRAFT_TABLE))
            applied.append("ai_draft_operation")
        else:
            columns = column_names(connection, "ai_draft_operation")
            for column, definition in (
                ("item_count", "INT UNSIGNED NOT NULL DEFAULT 0 AFTER order_no"),
                ("status", "VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'created' "
                           "AFTER item_count"),
            ):
                if column not in columns:
                    connection.execute(text(f"ALTER TABLE ai_draft_operation ADD COLUMN {column} {definition}"))
                    applied.append(f"ai_draft_operation.{column}")
    return applied


if __name__ == "__main__":
    changes = migrate(engine)
    print("已应用：" + ("，".join(changes) if changes else "无变更，数据库已是最新结构"))
