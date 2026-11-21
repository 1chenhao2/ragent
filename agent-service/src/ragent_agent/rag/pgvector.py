from __future__ import annotations

import asyncio
from collections.abc import Mapping
from typing import Any

from ragent_agent.rag.errors import RagConfigurationError

PGVECTOR_SCHEMA_DIMENSION = 1536


class AsyncPgVectorStore:
    """Read-only adapter for the established Java pgvector table."""

    _QUERY = """
        SELECT id, content, metadata, 1 - (embedding <=> $1::vector) AS score
        FROM t_knowledge_vector
        WHERE collection_name = ANY($2::varchar[])
        ORDER BY embedding <=> $1::vector
        LIMIT $3
    """
    _TENANT_QUERY = """
        SELECT id, content, metadata, 1 - (embedding <=> $1::vector) AS score
        FROM t_knowledge_vector
        WHERE collection_name = ANY($2::varchar[])
          AND metadata ->> $4 = $5
        ORDER BY embedding <=> $1::vector
        LIMIT $3
    """

    def __init__(self, dsn: str, timeout_seconds: float) -> None:
        self._dsn = dsn
        self._timeout_seconds = timeout_seconds
        self._pool: Any = None
        self._pool_lock = asyncio.Lock()

    async def search(
        self,
        embedding: list[float],
        collection_names: tuple[str, ...],
        top_k: int,
        tenant_id: str | None,
        tenant_metadata_key: str | None,
    ) -> list[Mapping[str, Any]]:
        pool = await self._require_pool()
        vector = _vector_literal(embedding)
        async with pool.acquire() as connection, connection.transaction(readonly=True):
            await connection.execute("SET LOCAL hnsw.ef_search = 200")
            await connection.execute("SET LOCAL hnsw.iterative_scan = relaxed_order")
            if tenant_id is None:
                rows = await connection.fetch(self._QUERY, vector, list(collection_names), top_k)
            else:
                if tenant_metadata_key is None:
                    raise RagConfigurationError("A tenant metadata key is required for tenant-scoped vector retrieval")
                rows = await connection.fetch(
                    self._TENANT_QUERY,
                    vector,
                    list(collection_names),
                    top_k,
                    tenant_metadata_key,
                    tenant_id,
                )
        return [dict(row) for row in rows]

    async def aclose(self) -> None:
        if self._pool is not None:
            await self._pool.close()
            self._pool = None

    async def _require_pool(self):
        if self._pool is not None:
            return self._pool
        async with self._pool_lock:
            if self._pool is None:
                try:
                    import asyncpg
                except ImportError as error:
                    raise RagConfigurationError("The asyncpg dependency is required for pgvector RAG") from error
                self._pool = await asyncpg.create_pool(
                    dsn=self._dsn,
                    min_size=1,
                    max_size=5,
                    command_timeout=self._timeout_seconds,
                    server_settings={
                        "application_name": "ragent-agent-service",
                        "default_transaction_read_only": "on",
                    },
                )
        return self._pool


def _vector_literal(embedding: list[float]) -> str:
    return "[" + ",".join(str(value) for value in embedding) + "]"
