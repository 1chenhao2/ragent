from __future__ import annotations

from dataclasses import dataclass

from pydantic import BaseModel, ConfigDict, Field


class RagModel(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class KnowledgeBase(RagModel):
    id: str = Field(min_length=1)
    name: str = Field(min_length=1)
    collection_name: str = Field(min_length=1, alias="collectionName")
    embedding_model: str = Field(min_length=1, alias="embeddingModel")


class SourceReference(RagModel):
    index: int = Field(ge=1)
    doc_id: str = Field(min_length=1, alias="docId")
    doc_name: str | None = Field(default=None, alias="docName")
    source_type: str | None = Field(default=None, alias="sourceType")
    file_type: str | None = Field(default=None, alias="fileType")
    url: str | None = None
    excerpt: str = Field(min_length=1)


@dataclass(frozen=True)
class RagRetrievalRequest:
    query: str
    tenant_id: str | None
    knowledge_bases: tuple[KnowledgeBase, ...]


@dataclass(frozen=True)
class RagContext:
    prompt_context: str
    sources: tuple[SourceReference, ...]
