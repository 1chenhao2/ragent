from __future__ import annotations

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class AgentServiceSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="RAGENT_AGENT_", extra="ignore")

    service_version: str = "0.1.0"
    delegated_token_hmac_secret: str = Field(min_length=32)
    delegated_token_issuer: str = Field(min_length=1)
    delegated_token_audience: str = Field(min_length=1)
    business_tool_base_url: str = Field(default="http://127.0.0.1:8080", min_length=1)
    business_tool_timeout_seconds: float = Field(default=10.0, gt=0, le=120)
    rag_enabled: bool = False
    rag_vector_backend: str = "pgvector"
    rag_pg_dsn: str | None = None
    rag_tenant_metadata_key: str | None = None
    rag_top_k: int = Field(default=5, ge=1, le=20)
    rag_context_max_chars: int = Field(default=12000, ge=1000, le=64000)
    rag_timeout_seconds: float = Field(default=30.0, gt=0, le=120)
    rag_embedding_base_url: str | None = None
    rag_embedding_api_key: str | None = None
    rag_embedding_model: str = "Qwen/Qwen3-Embedding-8B"
    rag_embedding_dimension: int = Field(default=1536, ge=1, le=8192)
    ingestion_enabled: bool = False
    ingestion_timeout_seconds: float = Field(default=60.0, gt=0, le=300)
    ingestion_embedding_model_id: str = "qwen-emb-8b"
    rag_chat_base_url: str | None = None
    rag_chat_api_key: str | None = None
    rag_chat_model: str | None = None
    run_store_backend: str = "memory"
    run_store_pg_dsn: str | None = None
    run_store_timeout_seconds: float = Field(default=10.0, gt=0, le=120)
    checkpoint_backend: str = "memory"
    checkpoint_pg_dsn: str | None = None
    checkpoint_timeout_seconds: float = Field(default=10.0, gt=0, le=120)
    auto_start_runs: bool = True
