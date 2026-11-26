import asyncio
import json
from collections.abc import AsyncIterator
from dataclasses import dataclass
from time import monotonic

import httpx
from pydantic import SecretStr

from app.core.config import settings

GENERATION_TIMEOUT_SECONDS = 150
SYSTEM_PROMPT = (
    "你是 WMS 仓储助手。使用用户的语言回答，优先给出清晰、可执行的仓储建议。"
    "你的上下文来自当前会话。遇到实时库存、单据状态和系统操作时，"
    "说明你只能依据用户和会话提供的信息，避免编造业务数据或声称已经执行操作。"
)


class ModelError(Exception):
    pass


@dataclass(frozen=True)
class ChatModel:
    endpoint: httpx.URL
    key: SecretStr
    model: str

    @classmethod
    def from_settings(cls) -> "ChatModel":
        base_url = settings.deepseek_base_url.strip()
        key = settings.deepseek_api_key.get_secret_value().strip()
        if not base_url or not key:
            raise ModelError("请先在 FastAPI 的 .env 中填写 Base URL 和 API Key，再重启服务。")
        try:
            url = httpx.URL(base_url)
        except (httpx.InvalidURL, ValueError):
            raise ModelError("模型 Base URL 格式有误。") from None
        if url.scheme not in {"http", "https"} or not url.host or url.username or url.password or url.fragment:
            raise ModelError("请填写有效的 HTTP(S) 模型 Base URL。")
        if not key.isascii() or any(character.isspace() for character in key):
            raise ModelError("模型 API Key 格式有误，请检查配置。")
        path = url.path.rstrip("/")
        if not path.endswith("/chat/completions"):
            path += "/chat/completions"
        model = settings.deepseek_model.strip() or "deepseek-flash"
        return cls(url.copy_with(path=path), SecretStr(key), model)

    def authorization(self) -> dict[str, str]:
        return {"Authorization": "Bearer " + self.key.get_secret_value()}

    async def stream(self, messages: list[dict[str, str]]) -> AsyncIterator[str]:
        request = {
            "model": self.model,
            "messages": [{"role": "system", "content": SYSTEM_PROMPT}, *messages],
            "stream": True,
            "max_tokens": 4096,
        }
        if self.endpoint.host == "api.deepseek.com":
            request["thinking"] = {"type": "disabled"}
        headers = {**self.authorization(), "Accept": "text/event-stream"}

        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(60, connect=10)) as client:
                async with client.stream("POST", self.endpoint, headers=headers, json=request) as response:
                    _check_response(response)
                    async for content in _response_tokens(response):
                        yield content
        except httpx.TimeoutException:
            raise ModelError("模型服务响应超时，请稍后重试。") from None
        except httpx.RequestError:
            raise ModelError("模型连接中断或无法建立，请检查 Base URL 与网络。") from None


@dataclass(frozen=True)
class ModelOption:
    id: str
    name: str


_catalog: tuple[ChatModel, float, tuple[ModelOption, ...]] | None = None
_catalog_lock = asyncio.Lock()


async def list_chat_models(model: ChatModel) -> tuple[ModelOption, ...]:
    global _catalog
    async with _catalog_lock:
        if _catalog is not None and _catalog[0] == model and monotonic() < _catalog[1]:
            return _catalog[2]
        options = await _fetch_models(model)
        _catalog = (model, monotonic() + (120 if options else 10), options)
        return options


async def _fetch_models(model: ChatModel) -> tuple[ModelOption, ...]:
    path = model.endpoint.path.removesuffix("/chat/completions") + "/models"
    endpoint = model.endpoint.copy_with(path=path)
    try:
        async with asyncio.timeout(5), httpx.AsyncClient() as client:
            response = await client.get(endpoint, headers=model.authorization())
            response.raise_for_status()
            payload = response.json()
    except (TimeoutError, httpx.HTTPError, ValueError):
        return ()

    entries = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(entries, list):
        return ()
    options = {}
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        model_id = entry.get("id")
        if not isinstance(model_id, str) or not model_id or model_id != model_id.strip() or len(model_id) > 256:
            continue
        modalities = [entry.get(key) for key in ("input_modalities", "output_modalities")]
        if any(isinstance(values, list) and "text" not in values for values in modalities):
            continue
        name = entry.get("name")
        label = name.strip()[:160] if isinstance(name, str) else ""
        options.setdefault(model_id, ModelOption(model_id, label or model_id))
    return tuple(options.values())


def _check_response(response: httpx.Response) -> None:
    code = response.status_code
    if code in (401, 403):
        raise ModelError("模型服务拒绝鉴权，请检查 FastAPI 中的 API Key。")
    if code in (400, 404, 422):
        raise ModelError(f"模型接口返回 {code}，请检查 Base URL 和网关支持的模型标识。")
    if code == 429:
        raise ModelError("模型请求达到频率或额度限制，请稍后重试。")
    if code != 200:
        raise ModelError(f"模型服务返回 HTTP {code}，请检查网关状态。")
    media_type = response.headers.get("content-type", "").split(";", 1)[0].strip().lower()
    if media_type != "text/event-stream":
        raise ModelError("模型网关未返回 SSE，请检查 Chat Completions 流式接口。")


async def _sse_payloads(response: httpx.Response) -> AsyncIterator[str]:
    lines: list[str] = []
    async for line in response.aiter_lines():
        if not line and lines:
            yield "\n".join(lines)
            lines.clear()
        elif line.startswith("data:"):
            lines.append(line[5:].removeprefix(" "))
    if lines:
        yield "\n".join(lines)


async def _response_tokens(response: httpx.Response) -> AsyncIterator[str]:
    finished = False
    async for data in _sse_payloads(response):
        if data.strip() == "[DONE]":
            return
        try:
            chunk = json.loads(data)
            if not isinstance(chunk, dict) or chunk.get("error"):
                raise ValueError
            choices = chunk.get("choices") or []
            if not choices:
                continue
            choice = choices[0]
            delta = choice.get("delta") or {}
            content = delta.get("content")
            finish_reason = choice.get("finish_reason")
        except (AttributeError, KeyError, TypeError, ValueError):
            raise ModelError("模型网关返回了无效的流式数据。") from None
        if isinstance(content, str) and content:
            yield content
        finished = finished or bool(finish_reason)
    if not finished:
        raise ModelError("模型回复提前中断，请重试。")
