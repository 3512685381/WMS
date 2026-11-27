import base64
from dataclasses import dataclass
import re
from time import time
from typing import Annotated
from uuid import UUID

from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
import jwt
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.core.config import settings
from app.core.database import DbSession


@dataclass(frozen=True, slots=True)
class AuthenticatedUser:
    id: int
    username: str
    roles: tuple[str, ...]
    permissions: frozenset[str]
    jti: str
    expires_at: int


def decode_signing_key(encoded: str) -> bytes:
    if not re.fullmatch(r"[A-Za-z0-9_-]+={0,2}", encoded):
        raise ValueError("JWT_SECRET 必须是 Base64URL 编码的密钥")
    if "=" in encoded and len(encoded) % 4:
        raise ValueError("JWT_SECRET 的 Base64URL 补位无效")
    padded = encoded + "=" * (-len(encoded) % 4)
    try:
        key = base64.b64decode(padded, altchars=b"-_", validate=True)
    except ValueError:
        raise ValueError("JWT_SECRET 的 Base64URL 编码无效") from None
    if len(key) < 32:
        raise ValueError("JWT_SECRET 解码后须至少有 32 字节")
    return key


SIGNING_KEY = decode_signing_key(settings.jwt_secret.get_secret_value())
BEARER = HTTPBearer(auto_error=False, scheme_name="WmsBearer")
USER_ACCESS = text("""
    SELECT DISTINCT u.id, u.username, r.role_code, m.perms
    FROM sys_user u
    LEFT JOIN sys_user_role ur ON ur.user_id = u.id AND ur.is_deleted = 0
    LEFT JOIN sys_role r ON r.id = ur.role_id AND r.is_deleted = 0 AND r.status = 1
    LEFT JOIN sys_role_menu rm ON rm.role_id = r.id AND rm.is_deleted = 0
    LEFT JOIN sys_menu m ON m.id = rm.menu_id AND m.is_deleted = 0 AND m.perms <> ''
    WHERE u.id = :user_id AND u.status = 1 AND u.is_deleted = 0
      AND NOT EXISTS (SELECT 1 FROM sys_token_blacklist WHERE jti = :jti)
    ORDER BY r.role_code
    """)


def unauthorized(message: str) -> HTTPException:
    return HTTPException(401, message, headers={"WWW-Authenticate": "Bearer"})


def load_active_user(db: Session, user_id: int, jti: str, expires_at: int) -> AuthenticatedUser:
    if expires_at <= time():
        raise unauthorized("登录凭证已过期")
    rows = db.execute(USER_ACCESS, {"user_id": user_id, "jti": jti}).mappings().all()
    if not rows:
        raise unauthorized("登录凭证已失效")
    roles = tuple(dict.fromkeys(row["role_code"] for row in rows if row["role_code"] is not None))
    if not roles:
        raise HTTPException(403, "当前账号暂无可用角色")
    permissions = frozenset(row["perms"] for row in rows if row["perms"])
    return AuthenticatedUser(rows[0]["id"], rows[0]["username"], roles, permissions, jti, expires_at)


def get_current_user(
    db: DbSession,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(BEARER)],
) -> AuthenticatedUser:
    if credentials is None:
        raise unauthorized("请先登录")

    try:
        claims = jwt.decode(
            credentials.credentials,
            SIGNING_KEY,
            algorithms=["HS256"],
            options={"require": ["sub", "jti", "exp"]},
        )
        subject = claims["sub"]
        jti = claims["jti"]
        expires_at = claims["exp"]
        if type(expires_at) is not int or not re.fullmatch(r"[1-9][0-9]{0,18}", subject) or str(UUID(jti)) != jti:
            raise ValueError
        user_id = int(subject)
        if user_id > 2**63 - 1:
            raise ValueError
    except (jwt.InvalidTokenError, ValueError, TypeError, AttributeError, OverflowError):
        raise unauthorized("登录凭证无效或已过期") from None

    return load_active_user(db, user_id, jti, expires_at)


CurrentUser = Annotated[AuthenticatedUser, Depends(get_current_user)]
