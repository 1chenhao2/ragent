from __future__ import annotations

import asyncio

import psycopg
import pytest
from langgraph.checkpoint.memory import InMemorySaver

from ragent_agent.config import AgentServiceSettings
from ragent_agent.runtime.checkpoint import InMemoryCheckpointStore, create_checkpoint_store
from ragent_agent.runtime.graph import LangGraphAgentRuntime

TEST_SECRET = "test-delegated-token-secret-0123456789"


def make_settings(**overrides: object) -> AgentServiceSettings:
    values: dict[str, object] = {
        "delegated_token_hmac_secret": TEST_SECRET,
        "delegated_token_issuer": "ragent-java",
        "delegated_token_audience": "ragent-agent-service",
    }
    values.update(overrides)
    return AgentServiceSettings(**values)


def test_checkpoint_store_defaults_to_memory() -> None:
    store = create_checkpoint_store(make_settings())

    assert type(store) is InMemoryCheckpointStore
    assert isinstance(store.checkpointer, InMemorySaver)


def test_checkpoint_store_requires_postgres_dsn() -> None:
    with pytest.raises(ValueError, match="RAGENT_AGENT_CHECKPOINT_PG_DSN"):
        create_checkpoint_store(make_settings(checkpoint_backend="postgres"))


def test_checkpoint_store_rejects_unknown_backend() -> None:
    with pytest.raises(ValueError, match="Unsupported checkpoint backend"):
        create_checkpoint_store(make_settings(checkpoint_backend="redis"))


def test_postgres_checkpoint_store_does_not_fake_success_without_a_database() -> None:
    store = create_checkpoint_store(
        make_settings(
            checkpoint_backend="postgres",
            checkpoint_pg_dsn="postgresql://invalid:invalid@127.0.0.1:1/invalid",
        )
    )

    async def initialize() -> None:
        with pytest.raises(psycopg.Error):
            await store.initialize()

    asyncio.run(initialize())


def test_runtime_initializes_and_closes_injected_checkpoint_store() -> None:
    class RecordingCheckpointStore:
        checkpointer = InMemorySaver()

        def __init__(self) -> None:
            self.initialized = False
            self.closed = False

        async def initialize(self) -> None:
            self.initialized = True

        async def aclose(self) -> None:
            self.closed = True

    store = RecordingCheckpointStore()
    runtime = LangGraphAgentRuntime(make_settings(), checkpoint_store=store)

    async def lifecycle() -> None:
        await runtime.initialize()
        assert store.initialized is True
        await runtime.aclose()
        assert store.closed is True

    asyncio.run(lifecycle())


def test_postgres_checkpoint_store_requires_initialization_before_access() -> None:
    from ragent_agent.runtime.checkpoint import PostgresCheckpointStore

    store = PostgresCheckpointStore("postgresql://invalid")
    with pytest.raises(RuntimeError, match="has not been initialized"):
        _ = store.checkpointer
