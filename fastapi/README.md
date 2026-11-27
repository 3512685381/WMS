# WMS FastAPI

本服务连接 WMS 的 MySQL 数据库。会话接口接受 Spring Boot 签发的 HS256 Token，并检查账号状态、有效角色和退出黑名单。

## 本地启动

```bash
python3.15 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

从 FastAPI 项目目录执行。运行状态为 `GET /ai/health`，OpenAPI 文档在 `/docs`。

在 PyCharm 中打开本目录后，从右上角运行配置列表选择 **FastAPI (Uvicorn)** 并运行。配置保存在 `.run/FastAPI.run.xml`，使用项目的 Python 解释器，以项目根目录为工作目录执行 `uvicorn` 模块，参数为 `app.main:app --host 127.0.0.1 --port 8000 --reload`。

`app/main.py` 定义 FastAPI 应用对象。Uvicorn 负责加载该对象并监听 HTTP 请求。

配置由 `app/core/config.py` 中的 Pydantic Settings 统一加载，默认读取 FastAPI 项目根目录的 `.env`。系统环境变量优先于该文件。可从 `.env.example` 创建本地配置文件。

在 FastAPI 的 `.env` 中配置数据库连接和 JWT 密钥。

~~~dotenv
WMS_DB_HOST=127.0.0.1
WMS_DB_PORT=3306
WMS_DB_NAME=wms
WMS_DB_USER=root
WMS_DB_PASSWORD='替换为你的MySQL密码'
JWT_SECRET='复制springboot/.env中的JWT_SECRET值'
~~~

`JWT_SECRET` 使用 Base64URL 编码，须与 Spring Boot 的值完全一致，鉴权代码解码后用 HS256 验证 Java 签发的 Token。更换密钥时同步更新两端并重启服务。

数据库连接由以上 `WMS_DB_*` 字段构造。也可设置 `DATABASE_URL`，其优先于这些字段，格式为 `mysql+pymysql://用户名:密码@主机:端口/数据库?charset=utf8mb4`。URL 中的用户名和密码须进行 URL 编码，按字段配置时直接填写原值。

## 接入模型

在 FastAPI 项目根目录的 .env 中填写以下两项，然后重启 start.sh。

~~~dotenv
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_API_KEY=替换为你的模型密钥
~~~

Base URL 使用 OpenAI Chat Completions 兼容地址。默认模型为 DeepSeek Flash（deepseek-flash）。若网关使用不同的模型标识，可以通过 DEEPSEEK_MODEL 覆盖。模型配置与上述数据库、JWT 配置一起保存在 FastAPI 的 `.env` 中，修改后重启服务生效。

工作台通过 `GET /ai/chat/models` 读取网关提供的模型，并可在 `POST /ai/chat/stream` 的请求体中指定 `model`。省略 `model` 时优先使用配置的模型。模型列表中缺少该标识时使用列表首项。

模型网关需支持 Chat Completions 的函数工具调用才能使用四个仓储专家。会话及运行记录保存在 MySQL。

## 会话接口

通过 Java 的 `POST /api/auth/login` 获取 `data.token`，随后为以下请求携带 `Authorization: Bearer <token>`。

| 方法和路径 | 用途 |
| --- | --- |
| `GET /ai/sessions` | 列出本人会话，按更新时间倒序，支持 `limit` 和 `offset` |
| `POST /ai/sessions` | 创建会话，请求体可传 `title`，返回 `data.session_id` |
| `PUT /ai/sessions/{session_id}` | 传入新 `title` 改名 |
| `DELETE /ai/sessions/{session_id}` | 删除本人会话，数据库级联清理消息、运行、步骤和草稿回执 |
| `GET /ai/sessions/{session_id}/messages` | 读取消息、运行状态、统计、执行步骤和草稿卡片，支持 `limit` 与 `before_id` 分页 |
| `GET /ai/runs/{run_id}` | 读取已有运行：状态、回复文本、步骤与草稿，页面重连使用 |
| `POST /ai/runs/{run_id}/cancel` | 收敛失去执行者的运行；仍有心跳时返回 409 |

响应统一使用 `code`、`msg` 和 `data`。表结构来自项目根目录的 `wms.sql`，已有库执行 `python migrations/m2026_11_27_ai_agent_contract.py` 补齐新增列与 `ai_draft_operation`。

接口、事件、权限矩阵与验收用例的完整基线见 [`docs/agent-contract.md`](docs/agent-contract.md)。

## 多智能体对话

`POST /ai/chat/stream` 接受
`{"session_id":"会话 UUID","query":"查询中央仓的可用库存","model":"可选模型标识","client_request_id":"可选请求标识"}`，
响应为 `text/event-stream`。

| 事件 | 用途 |
| --- | --- |
| `start` | 返回本轮消息 ID、运行 ID、是否重放、操作判定与缺失字段 |
| `dispatch`、`agent_start`、`agent_end` | 专家分派、执行状态和耗时，`call_id` 区分并发调用 |
| `tool_start`、`tool_end` | 同一工具调用共享 `step_order`，返回参数、结果摘要和耗时 |
| `summarize`、`token` | 主管汇总和逐段输出的 Markdown 文本 |
| `done`、`error` | 回复统计、草稿卡片或本轮错误 |

库存、单据、预警和统计由四个专家按需执行。工具权限来自当前用户在 WMS 中的角色菜单和按钮权限。客户端中断后，历史接口可读取已生成的回复、步骤以及 `interrupted` 状态。

### 请求去重与重试

客户端为每次发送生成 `client_request_id`，网络重试沿用同一个值。服务端以 `(session_id, client_request_id)` 唯一约束去重：

- 已结束的请求重复提交时直接回放已保存的文本与草稿，不重新执行；
- 仍在执行的请求重复提交时返回 409，可通过 `/ai/runs/{run_id}` 读取进度；
- 不传该字段时无法跨请求去重。

### 建单授权

“只有用户明确要求才建草稿”由服务端策略判定，不依赖模型：查询、统计、建议类请求只装配只读工具；显式建单但缺少仓库、商品、数量、批次或权限时进入待补充状态并追问；只有参数齐全时才装配对应建单工具，并在执行前校验操作类型、权限和参数摘要。

拥有建单权限的用户可明确要求代建入库或出库草稿。服务校验关联档案与出库可用量，并原子保存单据主表、明细与草稿回执，标记为 `status=0`、`source=AI_AGENT`。审核、库存扣减和流水入账由 Spring Boot 业务流程负责；草稿创建时的库存检查不预留库存。

### 运行恢复

步骤、回复文本和心跳每秒增量落库。进程重启或心跳超时后，遗留的 `running` 记录会被收敛为 `interrupted`，页面读取 `/ai/runs/{run_id}` 即可恢复已生成内容和单号，不会重新执行建单。

### 会话记忆

超出上下文的较早对话会压缩为 `ai_session.context_summary`，并用 `context_summary_through_message_id` 标记覆盖范围；最近 6 个完整轮次保留原文。已确认的仓库、商品、批次与草稿结果保存在 `context_state`，用于理解“还是这个仓库”“按刚才的数量”，但库存数量、单据状态仍需重新查询。摘要生成使用同一个模型网关，失败只记录日志，不影响本轮回复。

## 测试

```bash
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/python -m pytest -q
```

测试覆盖策略放行、草稿幂等与缺口回滚、工具分页与预警口径、记忆预算、汇总压缩、SSE 幂等重放、运行查询与取消、越权读取。数据库集成测试在单连接事务内执行并整体回滚。

