from __future__ import annotations

import pytest

from ragent_agent.api.app import create_app
from ragent_agent.config import AgentServiceSettings
from ragent_agent.runtime.store import RunStore, create_run_store

TEST_SECRET = "test-delegated-token-secret-0123456789"


def make_settings(**overrides: object) -> AgentServiceSettings:
    values: dict[str, object] = {
        "delegated_token_hmac_secret": TEST_SECRET,
        "delegated_token_issuer": "ragent-java",
        "delegated_token_audience": "ragent-agent-service",
        "auto_start_runs": False,
    }
    values.update(overrides)
    return AgentServiceSettings(**values)


def test_create_run_store_defaults_to_memory() -> None:
    store = create_run_store(make_settings())

    assert type(store) is RunStore


def test_create_run_store_requires_postgres_dsn() -> None:
    with pytest.raises(ValueError, match="RAGENT_AGENT_RUN_STORE_PG_DSN"):
        create_run_store(make_settings(run_store_backend="postgres"))


def test_create_run_store_rejects_unknown_backend() -> None:
    with pytest.raises(ValueError, match="Unsupported RAGENT_AGENT_RUN_STORE_BACKEND"):
        create_run_store(make_settings(run_store_backend="redis"))


def test_app_lifespan_initializes_and_closes_configured_store(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[str] = []

    class LifecycleStore(RunStore):
        async def initialize(self) -> None:
            calls.append("initialize")

        async def aclose(self) -> None:
            calls.append("aclose")

    monkeypatch.setattr("ragent_agent.api.app.create_run_store", lambda settings: LifecycleStore())

    from fastapi.testclient import TestClient

    with TestClient(create_app(make_settings())) as client:
        assert client.app.state.run_store.__class__ is LifecycleStore
        assert calls == ["initialize"]

    assert calls == ["initialize", "aclose"]
