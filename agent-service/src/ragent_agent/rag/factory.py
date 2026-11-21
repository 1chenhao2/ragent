from __future__ import annotations

from ragent_agent.config import AgentServiceSettings
from ragent_agent.rag.answer import AnswerGenerator, LangChainAnswerGenerator, LangChainOpenAIEmbeddings
from ragent_agent.rag.errors import RagConfigurationError
from ragent_agent.rag.pgvector import PGVECTOR_SCHEMA_DIMENSION, AsyncPgVectorStore
from ragent_agent.rag.retriever import PgVectorRagRetriever, RagRetriever


def create_retriever(settings: AgentServiceSettings) -> RagRetriever:
    if settings.rag_vector_backend != "pgvector":
        raise RagConfigurationError(f"Unsupported RAG vector backend: {settings.rag_vector_backend}")
    if settings.rag_embedding_dimension != PGVECTOR_SCHEMA_DIMENSION:
        raise RagConfigurationError(
            f"RAG embedding dimension must match t_knowledge_vector vector({PGVECTOR_SCHEMA_DIMENSION})"
        )
    _require(settings.rag_pg_dsn, "RAGENT_AGENT_RAG_PG_DSN")
    _require(settings.rag_embedding_base_url, "RAGENT_AGENT_RAG_EMBEDDING_BASE_URL")
    _require(settings.rag_embedding_api_key, "RAGENT_AGENT_RAG_EMBEDDING_API_KEY")
    return PgVectorRagRetriever(
        embeddings=LangChainOpenAIEmbeddings(
            base_url=settings.rag_embedding_base_url,
            api_key=settings.rag_embedding_api_key,
            model=settings.rag_embedding_model,
            dimension=settings.rag_embedding_dimension,
            timeout_seconds=settings.rag_timeout_seconds,
        ),
        vector_store=AsyncPgVectorStore(settings.rag_pg_dsn, settings.rag_timeout_seconds),
        top_k=settings.rag_top_k,
        context_max_chars=settings.rag_context_max_chars,
        tenant_metadata_key=settings.rag_tenant_metadata_key,
    )


def create_answer_generator(settings: AgentServiceSettings) -> AnswerGenerator:
    _require(settings.rag_chat_base_url, "RAGENT_AGENT_RAG_CHAT_BASE_URL")
    _require(settings.rag_chat_api_key, "RAGENT_AGENT_RAG_CHAT_API_KEY")
    _require(settings.rag_chat_model, "RAGENT_AGENT_RAG_CHAT_MODEL")
    return LangChainAnswerGenerator(
        base_url=settings.rag_chat_base_url,
        api_key=settings.rag_chat_api_key,
        model=settings.rag_chat_model,
        timeout_seconds=settings.rag_timeout_seconds,
    )


def _require(value: str | None, name: str) -> None:
    if value is None or not value.strip():
        raise RagConfigurationError(f"{name} must be configured when RAG is enabled")
