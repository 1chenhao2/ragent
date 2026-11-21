from __future__ import annotations

import asyncio

import pytest

from ragent_agent.config import AgentServiceSettings
from ragent_agent.rag.canary import run_embedding_canary
from ragent_agent.rag.errors import RagConfigurationError


class FakeEmbeddings:
    async def embed_query(self, query: str) -> list[float]:
        assert query == "Ragent embedding canary"
        return [0.1] * 1536


def test_embedding_canary_reports_the_actual_provider_dimension() -> None:
    result = asyncio.run(run_embedding_canary(settings(), FakeEmbeddings()))

    assert result.embedding_model_id == "qwen-emb-8b"
    assert result.embedding_model == "Qwen/Qwen3-Embedding-8B"
    assert result.dimension == 1536
    assert result.duration_ms >= 0


def test_embedding_canary_rejects_a_dimension_that_does_not_match_the_vector_schema() -> None:
    class WrongDimensionEmbeddings:
        async def embed_query(self, query: str) -> list[float]:
            return [0.1] * 1024

    with pytest.raises(ValueError, match="expected 1536"):
        asyncio.run(run_embedding_canary(settings(), WrongDimensionEmbeddings()))


def test_embedding_canary_requires_provider_configuration_before_making_a_call() -> None:
    incomplete_settings = AgentServiceSettings(
        delegated_token_hmac_secret="test-delegated-token-secret-0123456789",
        delegated_token_issuer="ragent-java",
        delegated_token_audience="ragent-agent-service",
    )

    with pytest.raises(RagConfigurationError, match="RAGENT_AGENT_RAG_EMBEDDING_BASE_URL"):
        asyncio.run(run_embedding_canary(incomplete_settings, FakeEmbeddings()))


def settings() -> AgentServiceSettings:
    return AgentServiceSettings(
        delegated_token_hmac_secret="test-delegated-token-secret-0123456789",
        delegated_token_issuer="ragent-java",
        delegated_token_audience="ragent-agent-service",
        rag_embedding_base_url="https://embedding.example.test/v1",
        rag_embedding_api_key="placeholder",
    )
