# WMS FastAPI

本服务连接 WMS 的 MySQL 数据库。会话接口接受 Spring Boot 签发的 HS256 Token，并检查账号状态、有效角色和退出黑名单。

## 本地启动

```bash
python3.15 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

从 FastAPI 项目目录执行。运行状态为 `GET /ai/health`，OpenAPI 文档在 `/docs`。

本地开发默认读取同级 Spring Boot 工程的 `.env`。环境变量和 FastAPI 的 `.env` 优先于共享文件。可用 `DATABASE_URL` 配置独立的 MySQL 连接。Java 和 FastAPI 使用同一份 Base64URL 编码的 `JWT_SECRET`。

## 接入模型

在 FastAPI 项目根目录的 .env 中填写以下两项，然后重启 start.sh。

~~~dotenv
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_API_KEY=替换为你的模型密钥
~~~

Base URL 使用 OpenAI Chat Completions 兼容地址。默认模型为 DeepSeek Flash（deepseek-flash）。若网关使用不同的模型标识，可以通过 DEEPSEEK_MODEL 覆盖。数据库和 JWT 继续读取共享的 Spring Boot 配置。

工作台通过 GET /ai/chat/models 读取网关提供的模型，并在 POST /ai/chat/stream 的请求体中传入 model。网关未提供列表或请求省略 model 时，使用已配置的默认模型。

这些接口使用 WMS Token。聊天接口仅访问当前用户自己的会话，并保存用户消息和模型回复。实时库存查询和多 Agent 工具调用待接入。

## 会话接口

通过 Java 的 `POST /api/auth/login` 获取 `data.token`，随后为以下请求携带 `Authorization: Bearer <token>`。

| 方法和路径 | 用途 |
| --- | --- |
| `GET /ai/sessions` | 列出本人会话，按更新时间倒序，支持 `limit` 和 `offset` |
| `POST /ai/sessions` | 创建会话，请求体可传 `title`，返回 `data.session_id` |
| `PUT /ai/sessions/{session_id}` | 传入新 `title` 改名 |
| `DELETE /ai/sessions/{session_id}` | 删除本人会话，数据库级联清理消息和步骤 |
| `GET /ai/sessions/{session_id}/messages` | 读取消息、运行状态、统计和执行步骤 |

响应统一使用 `code`、`msg` 和 `data`。表结构来自项目根目录的 `wms.sql`。
