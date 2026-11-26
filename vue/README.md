# 智能仓储 WMS 前端

## 本地启动

启动 Spring Boot（默认 8080）和 FastAPI（默认 8000），并确保它们共用已导入演示数据的 MySQL 库及 JWT 密钥。Node.js 需要 20.19+ 或 22.12+。

~~~bash
npm ci
npm run dev
~~~

浏览器打开 [WMS 本地工作台](http://127.0.0.1:5173)。使用 WMS 账号登录。开发环境可使用建库脚本提供的 admin、operator、user 演示账号。

Vite 将 /api 请求转发给 Spring Boot，将 /ai 请求转发给 FastAPI。端口和目标可在 .env.local 中配置，变量参考 .env.example。项目默认使用 5173。运行环境允许绑定 90 端口时，可将 WMS_WEB_PORT 设为 90。

## 当前可用功能

- 登录、当前账号和角色权限、菜单动态挂载及退出登录由 Spring Boot 提供。
- 管理员和作业员进入管理后台。普通用户进入前台工作台。
- 会话列表、新建、重命名、删除及 Markdown 历史消息和工具时间线由 FastAPI 提供。
- 配置 FastAPI 的模型 Base URL 和 API Key 后，AI 工作台支持选择模型、流式问答和停止生成，回复会保存到会话历史。
- 仓储业务模块等待 Spring Boot 提供业务接口。

运行 npm run build 生成 dist。独立部署时，站点需将 /api 与 /ai 反向代理到对应服务，并将前端路由回退到 index.html。
