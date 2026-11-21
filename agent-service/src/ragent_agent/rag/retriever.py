from __future__ import annotations

import json
import math
from collections.abc import Mapping
from typing import Any, Protocol

from llama_index.core.schema import NodeWithScore, TextNode

from ragent_agent.rag.errors import RagAuthorizationError
from ragent_agent.rag.models import RagContext, RagRetrievalRequest, SourceReference


class QueryEmbeddings(Protocol):
    async def embed_query(self, query: str) -> list[float]: ...


class VectorStore(Protocol):
    async def search(
        self,
        embedding: list[float],
        collection_names: tuple[str, ...],
        top_k: int,
        tenant_id: str | None,
        tenant_metadata_key: str | None,
    ) -> list[Mapping[str, Any]]: ...


class RagRetriever(Protocol):
    async def retrieve(self, request: RagRetrievalRequest) -> RagContext: ...


class PgVectorRagRetriever:
    """Converts the Java-owned pgvector schema into LlamaIndex evidence nodes."""

    def __init__(
        self,
        embeddings: QueryEmbeddings,
        vector_store: VectorStore,
        top_k: int,
        context_max_chars: int = 12000,
        tenant_metadata_key: str | None = None,
    ) -> None:
        self._embeddings = embeddings
        self._vector_store = vector_store
        self._top_k = top_k
        self._context_max_chars = context_max_chars
        self._tenant_metadata_key = tenant_metadata_key

    async def retrieve(self, request: RagRetrievalRequest) -> RagContext:
        if not request.knowledge_bases:
            return RagContext(prompt_context="", sources=())
        if request.tenant_id is not None and self._tenant_metadata_key is None:
            raise RagAuthorizationError("Tenant-scoped RAG requires RAGENT_AGENT_RAG_TENANT_METADATA_KEY")

        embedding = _normalize(await self._embeddings.embed_query(request.query))
        rows = await self._vector_store.search(
            embedding=embedding,
            collection_names=tuple(knowledge_base.collection_name for knowledge_base in request.knowledge_bases),
            top_k=self._top_k,
            tenant_id=request.tenant_id,
            tenant_metadata_key=self._tenant_metadata_key,
        )
        nodes = tuple(_to_node(row) for row in rows)
        return _to_context(nodes, self._context_max_chars)

    async def aclose(self) -> None:
        close = getattr(self._vector_store, "aclose", None)
        if close is not None:
            await close()


def _normalize(values: list[float]) -> list[float]:
    norm = math.sqrt(sum(value * value for value in values))
    if norm == 0:
        raise ValueError("Embedding provider returned a zero vector")
    return [value / norm for value in values]


def _to_node(row: Mapping[str, Any]) -> NodeWithScore:
    metadata = _metadata(row.get("metadata"))
    node = TextNode(
        id_=str(row["id"]),
        text=str(row.get("content") or ""),
        metadata=metadata,
    )
    score = row.get("score")
    return NodeWithScore(node=node, score=float(score) if score is not None else None)


def _metadata(value: Any) -> dict[str, Any]:
    if isinstance(value, Mapping):
        return dict(value)
    if isinstance(value, str):
        decoded = json.loads(value)
        if isinstance(decoded, dict):
            return decoded
    return {}


def _to_context(nodes: tuple[NodeWithScore, ...], context_max_chars: int) -> RagContext:
    source_index_by_doc_id: dict[str, int] = {}
    sources: list[SourceReference] = []
    context_parts: list[str] = []
    remaining = context_max_chars

    for node_with_score in nodes:
        node = node_with_score.node
        content = node.text.strip()
        if not content or remaining <= 0:
            continue
        metadata = node.metadata
        doc_id = str(metadata.get("doc_id") or node.node_id)
        index = source_index_by_doc_id.get(doc_id)
        if index is None:
            index = len(sources) + 1
            source_index_by_doc_id[doc_id] = index
            source_type = _optional_text(metadata.get("source_type"))
            source_location = _optional_text(metadata.get("source_location"))
            sources.append(
                SourceReference(
                    index=index,
                    docId=doc_id,
                    docName=_optional_text(metadata.get("doc_name")),
                    sourceType=source_type,
                    fileType=_optional_text(metadata.get("file_type")),
                    url=source_location if source_type in {"url", "feishu"} else None,
                    excerpt=_excerpt(node.text),
                )
            )

        prefix = f"[{index}] "
        if len(prefix) >= remaining:
            break
        part = prefix + content[: remaining - len(prefix)]
        context_parts.append(part)
        remaining -= len(part)

    return RagContext(prompt_context="\n\n".join(context_parts), sources=tuple(sources))


def _optional_text(value: Any) -> str | None:
    text = str(value).strip() if value is not None else ""
    return text or None


def _excerpt(text: str) -> str:
    normalized = text.strip()
    return normalized[:100] if len(normalized) > 100 else normalized
