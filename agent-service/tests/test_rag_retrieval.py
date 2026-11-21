from __future__ import annotations

import asyncio

import pytest

from ragent_agent.config import AgentServiceSettings
from ragent_agent.rag.errors import RagAuthorizationError, RagConfigurationError
from ragent_agent.rag.factory import create_retriever
from ragent_agent.rag.models import KnowledgeBase, RagRetrievalRequest
from ragent_agent.rag.retriever import PgVectorRagRetriever


def test_pgvector_retriever_limits_collections_normalizes_embedding_and_builds_citations() -> None:
    class FakeEmbeddings:
        async def embed_query(self, query: str) -> list[float]:
            assert query == "How do I respond to an incident?"
            return [3.0, 4.0]

    class FakeVectorStore:
        async def search(self, embedding, collection_names, top_k, tenant_id, tenant_metadata_key):
            assert embedding == [0.6, 0.8]
            assert collection_names == ("operations",)
            assert top_k == 3
            assert tenant_id == "tenant-1"
            assert tenant_metadata_key == "tenant_id"
            return [
                {
                    "id": "empty-chunk",
                    "content": "",
                    "metadata": {"doc_id": "empty-document"},
                    "score": 0.95,
                },
                {
                    "id": "chunk-1",
                    "content": "Escalate a critical incident to the incident commander.",
                    "metadata": {
                        "doc_id": "document-1",
                        "doc_name": "Incident handbook",
                        "source_type": "url",
                        "source_location": "https://example.test/incident-handbook",
                    },
                    "score": 0.91,
                }
            ]

    retriever = PgVectorRagRetriever(
        embeddings=FakeEmbeddings(),
        vector_store=FakeVectorStore(),
        top_k=3,
        tenant_metadata_key="tenant_id",
    )
    context = asyncio.run(
        retriever.retrieve(
            RagRetrievalRequest(
                query="How do I respond to an incident?",
                tenant_id="tenant-1",
                knowledge_bases=(
                    KnowledgeBase(
                        id="knowledge-1",
                        name="Operations",
                        collection_name="operations",
                        embedding_model="qwen-emb-8b",
                    ),
                ),
            )
        )
    )

    assert context.prompt_context == "[1] Escalate a critical incident to the incident commander."
    assert context.sources[0].model_dump(by_alias=True, exclude_none=True) == {
        "index": 1,
        "docId": "document-1",
        "docName": "Incident handbook",
        "sourceType": "url",
        "url": "https://example.test/incident-handbook",
        "excerpt": "Escalate a critical incident to the incident commander.",
    }


def test_pgvector_retriever_rejects_tenant_scope_without_metadata_filter() -> None:
    class UnusedEmbeddings:
        async def embed_query(self, query: str) -> list[float]:
            raise AssertionError("Embedding must not run before tenant scope validation")

    class UnusedVectorStore:
        async def search(self, embedding, collection_names, top_k, tenant_id, tenant_metadata_key):
            raise AssertionError("Vector search must not run before tenant scope validation")

    retriever = PgVectorRagRetriever(
        embeddings=UnusedEmbeddings(),
        vector_store=UnusedVectorStore(),
        top_k=3,
    )

    with pytest.raises(RagAuthorizationError, match="RAGENT_AGENT_RAG_TENANT_METADATA_KEY"):
        asyncio.run(
            retriever.retrieve(
                RagRetrievalRequest(
                    query="How do I respond to an incident?",
                    tenant_id="tenant-1",
                    knowledge_bases=(
                        KnowledgeBase(
                            id="knowledge-1",
                            name="Operations",
                            collection_name="operations",
                            embedding_model="qwen-emb-8b",
                        ),
                    ),
                )
            )
        )


def test_pgvector_factory_rejects_dimension_that_does_not_match_existing_schema() -> None:
    settings = AgentServiceSettings(
        delegated_token_hmac_secret="test-delegated-token-secret-0123456789",
        delegated_token_issuer="ragent-java",
        delegated_token_audience="ragent-agent-service",
        rag_pg_dsn="postgresql://user:password@localhost/ragent",
        rag_embedding_base_url="https://embedding.example.test/v1",
        rag_embedding_api_key="placeholder",
        rag_embedding_dimension=1024,
    )

    with pytest.raises(RagConfigurationError, match=r"vector\(1536\)"):
        create_retriever(settings)
