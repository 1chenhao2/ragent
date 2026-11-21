from __future__ import annotations

from datetime import UTC, datetime, timedelta

import jwt
import pytest
from fastapi.testclient import TestClient

from ragent_agent.api.app import create_app
from ragent_agent.config import AgentServiceSettings
from ragent_agent.ingestion.text import (
    EmbeddingProviderError,
    LangChainLlamaIndexTextIngestionService,
    TextIngestionResult,
    TextIngestionService,
    _chunk_blocks,
)
from ragent_agent.rag.errors import RagConfigurationError

TEST_SECRET = "test-delegated-token-secret-0123456789"


class FakeTextIngestionService(TextIngestionService):
    async def compute(self, request):
        assert request.document_name == "handbook.md"
        assert request.mime_type == "text/markdown"
        assert request.chunking_mode == "structure_aware"
        assert request.chunk_config == {"targetChars": 240}
        return TextIngestionResult(
            chunks=(
                {
                    "index": 0,
                    "content": "# Handbook\n\nEscalate incidents.",
                    "embedding": [0.25, 0.75],
                    "metadata": {"outlinePath": ["Handbook"]},
                },
            ),
            parse_duration_ms=3,
            chunk_duration_ms=5,
            embed_duration_ms=7,
        )


class FailingTextIngestionService(TextIngestionService):
    async def compute(self, request):
        raise EmbeddingProviderError("provider did not return a usable embedding")


def test_text_ingestion_requires_the_dedicated_scope() -> None:
    client = TestClient(create_app(settings(), text_ingestion=FakeTextIngestionService()))

    response = client.post("/internal/v1/ingestion/text-chunks", headers=headers({"knowledge:read"}), json=payload())

    assert response.status_code == 403
    assert response.json()["code"] == "missing_ingestion_compute_scope"


def test_text_ingestion_returns_embedded_chunks_without_business_database_access() -> None:
    client = TestClient(create_app(settings(), text_ingestion=FakeTextIngestionService()))

    response = client.post(
        "/internal/v1/ingestion/text-chunks",
        headers=headers({"ingestion:compute"}),
        json=payload(),
    )

    assert response.status_code == 200
    assert response.json() == {
        "chunks": [
            {
                "index": 0,
                "content": "# Handbook\n\nEscalate incidents.",
                "embedding": [0.25, 0.75],
                "metadata": {"outlinePath": ["Handbook"]},
            }
        ],
        "timings": {
            "parseDurationMs": 3,
            "chunkDurationMs": 5,
            "embedDurationMs": 7,
        },
    }


def test_text_ingestion_maps_embedding_provider_failures_to_bad_gateway() -> None:
    client = TestClient(create_app(settings(), text_ingestion=FailingTextIngestionService()))

    response = client.post(
        "/internal/v1/ingestion/text-chunks",
        headers=headers({"ingestion:compute"}),
        json=payload(),
    )

    assert response.status_code == 502
    assert response.json()["code"] == "embedding_provider_failed"


def test_structure_aware_chunking_preserves_markdown_block_content() -> None:
    chunks = _chunk_blocks(
        [
            {
                "content": "# Handbook",
                "embeddingText": "# Handbook",
                "metadata": {"sourceFile": "handbook.md", "outlinePath": ["Handbook"]},
            },
            {
                "content": "Escalate incidents.",
                "embeddingText": "Escalate incidents.",
                "metadata": {"sourceFile": "handbook.md", "outlinePath": ["Handbook", "Incidents"]},
            },
        ],
        "structure_aware",
        {"targetChars": 240, "overlapChars": 0},
    )

    assert [chunk["content"] for chunk in chunks] == ["# Handbook", "Escalate incidents."]
    assert [chunk["metadata"] for chunk in chunks] == [
        {"sourceFile": "handbook.md", "outlinePath": ["Handbook"]},
        {"sourceFile": "handbook.md", "outlinePath": ["Handbook", "Incidents"]},
    ]


def test_fixed_size_chunking_keeps_only_metadata_shared_by_merged_blocks() -> None:
    chunks = _chunk_blocks(
        [
            {
                "content": "# Handbook",
                "metadata": {"sourceFile": "handbook.md", "outlinePath": ["Handbook"]},
            },
            {
                "content": "Escalate incidents.",
                "metadata": {"sourceFile": "handbook.md", "outlinePath": ["Handbook", "Incidents"]},
            },
        ],
        "fixed_size",
        {"chunkSize": 240, "overlapSize": 0},
    )

    assert len(chunks) == 1
    assert chunks[0]["metadata"] == {"sourceFile": "handbook.md"}


def test_text_ingestion_rejects_missing_embedding_configuration_at_startup() -> None:
    with pytest.raises(RagConfigurationError, match="RAGENT_AGENT_RAG_EMBEDDING_BASE_URL"):
        LangChainLlamaIndexTextIngestionService(settings())


def settings() -> AgentServiceSettings:
    return AgentServiceSettings(
        delegated_token_hmac_secret=TEST_SECRET,
        delegated_token_issuer="ragent-java",
        delegated_token_audience="ragent-agent-service",
        auto_start_runs=False,
        ingestion_enabled=True,
    )


def headers(scopes: set[str]) -> dict[str, str]:
    now = datetime.now(UTC)
    token = jwt.encode(
        {
            "sub": "system-ingestion",
            "iss": "ragent-java",
            "aud": "ragent-agent-service",
            "iat": now,
            "exp": now + timedelta(minutes=5),
            "roles": ["system"],
            "allowedKnowledgeBaseIds": [],
            "toolScopes": sorted(scopes),
        },
        TEST_SECRET,
        algorithm="HS256",
    )
    return {"Authorization": f"Bearer {token}"}


def payload() -> dict[str, object]:
    return {
        "documentName": "handbook.md",
        "mimeType": "text/markdown",
        "content": "# Handbook\n\nEscalate incidents.",
        "chunkingMode": "structure_aware",
        "chunkConfig": {"targetChars": 240},
        "embeddingModelId": "qwen-emb-8b",
    }
