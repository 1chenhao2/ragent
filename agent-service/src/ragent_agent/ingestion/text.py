from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any, Protocol

from pydantic import BaseModel, ConfigDict, Field

from ragent_agent.config import AgentServiceSettings
from ragent_agent.rag.answer import LangChainOpenAIEmbeddings
from ragent_agent.rag.errors import RagConfigurationError


class TextIngestionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    document_name: str = Field(min_length=1, max_length=512, alias="documentName")
    mime_type: str = Field(min_length=1, max_length=128, alias="mimeType")
    content: str = Field(min_length=1, max_length=4_000_000)
    chunking_mode: str = Field(default="structure_aware", alias="chunkingMode")
    chunk_config: dict[str, int] = Field(default_factory=dict, alias="chunkConfig")
    embedding_model_id: str | None = Field(default=None, alias="embeddingModelId")


@dataclass(frozen=True)
class TextIngestionResult:
    chunks: tuple[dict[str, Any], ...]
    parse_duration_ms: int
    chunk_duration_ms: int
    embed_duration_ms: int


class TextIngestionService(Protocol):
    async def compute(self, request: TextIngestionRequest) -> TextIngestionResult: ...


class EmbeddingProviderError(RuntimeError):
    """Raised when a configured embedding provider cannot produce a usable response."""


class LangChainLlamaIndexTextIngestionService:
    def __init__(self, settings: AgentServiceSettings) -> None:
        if not settings.rag_embedding_base_url or not settings.rag_embedding_api_key:
            raise RagConfigurationError(
                "RAGENT_AGENT_RAG_EMBEDDING_BASE_URL and RAGENT_AGENT_RAG_EMBEDDING_API_KEY "
                "must be configured when ingestion is enabled"
            )
        self._embedding_model_id = settings.ingestion_embedding_model_id
        self._embeddings = LangChainOpenAIEmbeddings(
            base_url=settings.rag_embedding_base_url,
            api_key=settings.rag_embedding_api_key,
            model=settings.rag_embedding_model,
            dimension=settings.rag_embedding_dimension,
            timeout_seconds=settings.ingestion_timeout_seconds,
        )

    async def compute(self, request: TextIngestionRequest) -> TextIngestionResult:
        if request.mime_type not in {"text/markdown", "text/x-markdown", "text/plain"}:
            raise ValueError(f"Unsupported ingestion MIME type: {request.mime_type}")
        if request.embedding_model_id and request.embedding_model_id != self._embedding_model_id:
            raise ValueError(f"Unsupported ingestion embedding model: {request.embedding_model_id}")
        parse_started = time.perf_counter()
        blocks = _markdown_blocks(request.content, request.document_name)
        parse_duration_ms = _elapsed_ms(parse_started)

        chunk_started = time.perf_counter()
        chunks = _chunk_blocks(blocks, request.chunking_mode, request.chunk_config)
        chunk_duration_ms = _elapsed_ms(chunk_started)

        embed_started = time.perf_counter()
        try:
            embeddings = await self._embeddings.embed_documents([chunk["embeddingText"] for chunk in chunks])
            for chunk, embedding in zip(chunks, embeddings, strict=True):
                chunk["embedding"] = embedding
                chunk.pop("embeddingText", None)
        except Exception as error:
            raise EmbeddingProviderError("Embedding provider did not return usable vectors") from error
        embed_duration_ms = _elapsed_ms(embed_started)
        return TextIngestionResult(tuple(chunks), parse_duration_ms, chunk_duration_ms, embed_duration_ms)


def _markdown_blocks(content: str, document_name: str) -> list[dict[str, Any]]:
    try:
        from llama_index.core import Document
        from llama_index.core.node_parser import MarkdownNodeParser
    except ImportError as error:
        raise RuntimeError("llama-index-core is required for text ingestion") from error
    nodes = MarkdownNodeParser().get_nodes_from_documents([Document(text=content)])
    return [
        {
            "content": node.get_content(),
            "embeddingText": node.get_content(),
            "blockType": "MARKDOWN",
            "metadata": {"sourceFile": document_name, **node.metadata},
        }
        for node in nodes
        if node.get_content().strip()
    ]


def _chunk_blocks(blocks: list[dict[str, Any]], mode: str, config: dict[str, int]) -> list[dict[str, Any]]:
    if not blocks:
        return []
    shared_metadata = _shared_metadata(blocks)
    if config.get("chunkSize") == -1 or config.get("targetChars") == -1:
        bodies = [("\n\n".join(block["content"] for block in blocks), shared_metadata)]
    elif mode == "fixed_size":
        size = max(1, config.get("chunkSize", 512))
        overlap = max(0, min(config.get("overlapSize", 128), size - 1))
        text = "\n\n".join(block["content"] for block in blocks)
        bodies = [(text[start:end], shared_metadata) for start, end in _fixed_ranges(len(text), size, overlap)]
    elif mode == "structure_aware":
        from llama_index.core.node_parser import SentenceSplitter

        splitter = SentenceSplitter(
            chunk_size=max(1, config.get("targetChars", 1400)),
            chunk_overlap=max(0, config.get("overlapChars", 0)),
        )
        bodies = [
            (node.text, dict(block.get("metadata", {})))
            for block in blocks
            for node in splitter.get_nodes_from_documents([_document(block["content"])])
        ]
    else:
        raise ValueError(f"Unsupported chunking mode: {mode}")
    return [
        {
            "index": index,
            "content": content,
            "embeddingText": content,
            "metadata": dict(metadata),
        }
        for index, (content, metadata) in enumerate(bodies)
        if content.strip()
    ]


def _shared_metadata(blocks: list[dict[str, Any]]) -> dict[str, Any]:
    shared = _block_metadata(blocks[0])
    missing = object()
    for block in blocks[1:]:
        metadata = _block_metadata(block)
        shared = {key: value for key, value in shared.items() if metadata.get(key, missing) == value}
    return shared


def _block_metadata(block: dict[str, Any]) -> dict[str, Any]:
    metadata = block.get("metadata")
    return dict(metadata) if isinstance(metadata, dict) else {}


def _document(text: str) -> Any:
    from llama_index.core import Document

    return Document(text=text)


def _fixed_ranges(length: int, size: int, overlap: int) -> list[tuple[int, int]]:
    ranges: list[tuple[int, int]] = []
    start = 0
    while start < length:
        end = min(start + size, length)
        ranges.append((start, end))
        if end == length:
            break
        start = end - overlap
    return ranges


def _elapsed_ms(started: float) -> int:
    return max(0, round((time.perf_counter() - started) * 1000))
