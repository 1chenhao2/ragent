from __future__ import annotations

from contextlib import AbstractAsyncContextManager
from typing import Any, Protocol

from langgraph.checkpoint.memory import InMemorySaver
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

from ragent_agent.config import AgentServiceSettings


class CheckpointStore(Protocol):
    @property
    def checkpointer(self) -> Any:
        """Return the LangGraph checkpointer used to compile the graph."""

    async def initialize(self) -> None:
        """Open external resources and prepare the checkpointer."""

    async def aclose(self) -> None:
        """Release external resources held by the checkpointer."""


class InMemoryCheckpointStore:
    def __init__(self) -> None:
        self._checkpointer = InMemorySaver()

    @property
    def checkpointer(self) -> InMemorySaver:
        return self._checkpointer

    async def initialize(self) -> None:
        return None

    async def aclose(self) -> None:
        return None


class PostgresCheckpointStore:
    def __init__(self, dsn: str) -> None:
        self._dsn = dsn
        self._context: AbstractAsyncContextManager[AsyncPostgresSaver] | None = None
        self._checkpointer: AsyncPostgresSaver | None = None

    @property
    def checkpointer(self) -> AsyncPostgresSaver:
        if self._checkpointer is None:
            raise RuntimeError("PostgreSQL checkpoint store has not been initialized")
        return self._checkpointer

    async def initialize(self) -> None:
        if self._checkpointer is not None:
            return
        context = AsyncPostgresSaver.from_conn_string(self._dsn)
        try:
            checkpointer = await context.__aenter__()
            await checkpointer.setup()
        except BaseException:
            await context.__aexit__(None, None, None)
            raise
        self._context = context
        self._checkpointer = checkpointer

    async def aclose(self) -> None:
        context = self._context
        self._context = None
        self._checkpointer = None
        if context is not None:
            await context.__aexit__(None, None, None)


def create_checkpoint_store(settings: AgentServiceSettings) -> CheckpointStore:
    backend = settings.checkpoint_backend.strip().lower()
    if backend == "memory":
        return InMemoryCheckpointStore()
    if backend == "postgres":
        if not settings.checkpoint_pg_dsn:
            raise ValueError("RAGENT_AGENT_CHECKPOINT_PG_DSN must be configured for PostgreSQL checkpoint storage")
        return PostgresCheckpointStore(settings.checkpoint_pg_dsn)
    raise ValueError(f"Unsupported checkpoint backend: {settings.checkpoint_backend}")
