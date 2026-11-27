from pathlib import Path

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy.engine import URL, make_url
from sqlalchemy.exc import ArgumentError

PROJECT_DIR = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=PROJECT_DIR / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
        hide_input_in_errors=True,
    )

    wms_db_host: str = "127.0.0.1"
    wms_db_port: int = Field(default=3306, ge=1, le=65535)
    wms_db_name: str = "wms"
    wms_db_user: str | None = None
    wms_db_password: SecretStr | None = None
    database_url: SecretStr | None = None
    jwt_secret: SecretStr
    deepseek_base_url: str = ""
    deepseek_api_key: SecretStr = SecretStr("")
    deepseek_model: str = "deepseek-flash"
    cors_origins: list[str] = Field(
        default_factory=lambda: [
            "http://127.0.0.1:90",
            "http://localhost:90",
            "http://127.0.0.1:5173",
            "http://localhost:5173",
        ]
    )

    def mysql_url(self) -> URL:
        if self.database_url is not None:
            try:
                url = make_url(self.database_url.get_secret_value())
            except (ArgumentError, ValueError):
                raise ValueError("DATABASE_URL 的格式无效") from None
            if url.drivername not in ("mysql", "mysql+pymysql") or not url.database:
                raise ValueError("DATABASE_URL 须指向 MySQL 数据库")
            url = url.set(drivername="mysql+pymysql")
            return url if "charset" in url.query else url.update_query_dict({"charset": "utf8mb4"})

        if self.wms_db_user is None or self.wms_db_password is None:
            raise ValueError("请配置 DATABASE_URL 或 WMS_DB_USER 和 WMS_DB_PASSWORD")
        return URL.create(
            "mysql+pymysql",
            username=self.wms_db_user,
            password=self.wms_db_password.get_secret_value(),
            host=self.wms_db_host,
            port=self.wms_db_port,
            database=self.wms_db_name,
            query={"charset": "utf8mb4"},
        )


settings = Settings()
