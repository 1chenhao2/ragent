from __future__ import annotations

import asyncio
import json
import time
from dataclasses import asdict, dataclass
from typing import Protocol

from ragent_agent.config import AgentServiceSettings
from ragent_agent.rag.answer import LangChainOpenAIEmbeddings
from ragent_agent.rag.errors import RagConfigurationError
from ragent_agent.rag.pgvector import PGVECTOR_SCHEMA_DIMENSION

CANARY_QUERY = "Ragent embedding canary"


class EmbeddingQueryClient(Protocol):
    async def embed_query(self, query: str) -> list[float]: ...


@dataclass(frozen=True)
class EmbeddingCanaryResult:
    embedding_model_id: str
    embedding_model: str
    dimension: int
    duration_ms: int


async def run_embedding_canary(
    settings: AgentServiceSettings,
    embeddings: EmbeddingQueryClient | None = None,
) -> EmbeddingCanaryResult:
    _validate_settings(settings)
    client = embeddings or LangChainOpenAIEmbeddings(
        base_url=settings.rag_embedding_base_url,
        api_key=settings.rag_embedding_api_key,
        model=settings.rag_embedding_model,
        dimension=settings.rag_embedding_dimension,
        timeout_seconds=settings.ingestion_timeout_seconds,
    )
    started = time.perf_counter()
    vector = await client.embed_query(CANARY_QUERY)
    duration_ms = max(0, round((time.perf_counter() - started) * 1000))
    if len(vector) != PGVECTOR_SCHEMA_DIMENSION:
        raise ValueError(
            f"Embedding provider returned {len(vector)} dimensions; expected {PGVECTOR_SCHEMA_DIMENSION}"
        )
    return EmbeddingCanaryResult(
        embedding_model_id=settings.ingestion_embedding_model_id,
        embedding_model=settings.rag_embedding_model,
        dimension=len(vector),
        duration_ms=duration_ms,
    )


def _validate_settings(settings: AgentServiceSettings) -> None:
    if settings.rag_embedding_dimension != PGVECTOR_SCHEMA_DIMENSION:
        raise RagConfigurationError(
            f"RAG embedding dimension must match t_knowledge_vector vector({PGVECTOR_SCHEMA_DIMENSION})"
        )
    if not settings.rag_embedding_base_url or not settings.rag_embedding_base_url.strip():
        raise RagConfigurationError("RAGENT_AGENT_RAG_EMBEDDING_BASE_URL must be configured for the embedding canary")
    if not settings.rag_embedding_api_key or not settings.rag_embedding_api_key.strip():
        raise RagConfigurationError("RAGENT_AGENT_RAG_EMBEDDING_API_KEY must be configured for the embedding canary")


def main() -> int:
    result = asyncio.run(run_embedding_canary(AgentServiceSettings()))
    print(json.dumps(asdict(result), separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
