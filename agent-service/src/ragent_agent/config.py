from __future__ import annotations

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class AgentServiceSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="RAGENT_AGENT_", extra="ignore")

    service_version: str = "0.1.0"
    delegated_token_hmac_secret: str = Field(min_length=32)
    delegated_token_issuer: str = Field(min_length=1)
    delegated_token_audience: str = Field(min_length=1)
    auto_start_runs: bool = True
