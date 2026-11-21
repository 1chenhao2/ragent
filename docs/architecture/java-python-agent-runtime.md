# Java to Python Agent Runtime

This document describes the Phase 4 runtime connection. The browser contract remains the existing Java SSE endpoint; only the `AgentGateway` adapter changes.

## Runtime selection

The legacy Java RAG adapter remains enabled when no runtime property is configured.

To enable the Python adapter, configure the Java process with:

```text
RAGENT_AGENT_RUNTIME=python
RAGENT_AGENT_PYTHON_BASE_URL=http://agent-service:8000
RAGENT_AGENT_PYTHON_CONNECT_TIMEOUT=3s
RAGENT_AGENT_PYTHON_REQUEST_TIMEOUT=10s
RAGENT_AGENT_PYTHON_STREAM_READ_TIMEOUT=90s
RAGENT_AGENT_PYTHON_RUN_TIMEOUT=5m
RAGENT_AGENT_PYTHON_DELEGATED_TOKEN_SECRET=<secret-from-secret-manager>
RAGENT_AGENT_PYTHON_DELEGATED_TOKEN_ISSUER=ragent-java
RAGENT_AGENT_PYTHON_DELEGATED_TOKEN_AUDIENCE=ragent-agent-service
RAGENT_AGENT_PYTHON_DELEGATED_TOKEN_BUSINESS_TOOL_AUDIENCE=ragent-business-tools
RAGENT_AGENT_PYTHON_DELEGATED_TOKEN_TTL=10m
RAGENT_AGENT_PYTHON_TEXT_INGESTION_ENABLED=false
RAGENT_AGENT_PYTHON_TEXT_INGESTION_FALLBACK_TO_JAVA=true
RAGENT_AGENT_PYTHON_TEXT_INGESTION_EMBEDDING_MODEL_ID=qwen-emb-8b
RAGENT_AGENT_PYTHON_TEXT_INGESTION_MAX_CONTENT_CHARS=1000000
RAGENT_AGENT_TOOL_IDEMPOTENCY_TTL=24h
RAGENT_AGENT_TOOL_LOCK_WAIT=3s
RAGENT_AGENT_TOOL_LOCK_LEASE=30s
```

The Python process must use matching delegated-token settings:

```text
RAGENT_AGENT_DELEGATED_TOKEN_HMAC_SECRET=<same-secret-from-secret-manager>
RAGENT_AGENT_DELEGATED_TOKEN_ISSUER=ragent-java
RAGENT_AGENT_DELEGATED_TOKEN_AUDIENCE=ragent-agent-service
RAGENT_AGENT_BUSINESS_TOOL_BASE_URL=http://bootstrap:8080
RAGENT_AGENT_BUSINESS_TOOL_TIMEOUT_SECONDS=10
RAGENT_AGENT_RAG_ENABLED=true
RAGENT_AGENT_RAG_VECTOR_BACKEND=pgvector
RAGENT_AGENT_RAG_PG_DSN=<postgres-dsn-from-secret-manager>
RAGENT_AGENT_RAG_TOP_K=5
RAGENT_AGENT_RAG_CONTEXT_MAX_CHARS=12000
RAGENT_AGENT_RAG_EMBEDDING_BASE_URL=https://api.siliconflow.cn/v1
RAGENT_AGENT_RAG_EMBEDDING_API_KEY=<key-from-secret-manager>
RAGENT_AGENT_RAG_EMBEDDING_MODEL=Qwen/Qwen3-Embedding-8B
RAGENT_AGENT_RAG_EMBEDDING_DIMENSION=1536
RAGENT_AGENT_INGESTION_ENABLED=false
RAGENT_AGENT_INGESTION_TIMEOUT_SECONDS=60
RAGENT_AGENT_INGESTION_EMBEDDING_MODEL_ID=qwen-emb-8b
RAGENT_AGENT_RAG_CHAT_BASE_URL=<openai-compatible-v1-url>
RAGENT_AGENT_RAG_CHAT_API_KEY=<key-from-secret-manager>
RAGENT_AGENT_RAG_CHAT_MODEL=<chat-model>
RAGENT_AGENT_RUN_STORE_BACKEND=postgres
RAGENT_AGENT_RUN_STORE_PG_DSN=<run-store-postgres-dsn-from-secret-manager>
RAGENT_AGENT_RUN_STORE_TIMEOUT_SECONDS=10
RAGENT_AGENT_CHECKPOINT_BACKEND=postgres
RAGENT_AGENT_CHECKPOINT_PG_DSN=<checkpoint-postgres-dsn-from-secret-manager>
RAGENT_AGENT_CHECKPOINT_TIMEOUT_SECONDS=10
```

The HMAC secret must contain at least 32 characters and must not be committed. The delegated-token TTL must exceed the Java Python-run timeout so late Tool calls and cancellation can still authenticate. RAG is disabled by default. Switching `RAGENT_AGENT_RUNTIME` back to `legacy`, removing it, or setting `RAGENT_AGENT_RAG_ENABLED=false` preserves the rollback path.

When `tenantId` is non-null, `RAGENT_AGENT_RAG_TENANT_METADATA_KEY` is mandatory and the same metadata key must be present on every indexed vector. Python refuses the retrieval before embedding or database access when that filter cannot be applied.

## Text ingestion migration

The first ingestion migration slice is disabled by default. With both `RAGENT_AGENT_PYTHON_TEXT_INGESTION_ENABLED=true` and `RAGENT_AGENT_INGESTION_ENABLED=true`, Java sends only UTF-8 `text/plain`, `text/markdown` and `text/x-markdown` content whose configured embedding model ID matches `RAGENT_AGENT_PYTHON_TEXT_INGESTION_EMBEDDING_MODEL_ID`. Python verifies a Java-issued `ingestion:compute` token, uses LlamaIndex for Markdown-aware segmentation and LangChain for batched embeddings, and returns in-memory chunks to Java.

Java continues to own file/object storage, document status, MQ scheduling, `t_knowledge_chunk`, `t_knowledge_vector` writes and the surrounding transaction. It generates persisted chunk IDs after receiving Python output. PDF, Office, image and custom pipeline inputs remain on the Java path. When `RAGENT_AGENT_PYTHON_TEXT_INGESTION_FALLBACK_TO_JAVA=true`, only `agent_service_unavailable` and `embedding_provider_failed` return to the existing Java parser/chunker/embedding flow before any database or vector write occurs. Invalid input and malformed Python responses fail closed, so parity defects are visible. Set fallback to `false` only during controlled parity testing.

## Run persistence and restart behavior

`RAGENT_AGENT_RUN_STORE_BACKEND=memory` is the default for local development and tests. Production uses `postgres`; apply `resources/database/upgrades/v1.1.0/260810_agent_runtime.sql` before deployment. The PostgreSQL adapter stores run state, idempotency keys and SSE events in the `ragent_agent` schema. It never stores delegated authorization tokens.

During startup, runs left in `running` are marked `failed` with the stable code `agent_service_restarted`, and one `run_failed` event is appended. Runs in `waiting_approval` remain resumable through the durable LangGraph checkpoint; Java reissues a current delegated token before calling resume. Terminal state and event history remain queryable, allowing Java to show an accurate interrupted result after a process restart.

The run store and LangGraph checkpoint are separate persistence concerns. With `RAGENT_AGENT_CHECKPOINT_BACKEND=postgres`, LangGraph workflow checkpoints survive process restarts. The approval resume endpoint accepts a fresh Java-issued delegated token and never persists that token. In-flight execution is still failed closed on restart rather than replaying unknown side effects.

## Request flow

1. Java authenticates the browser request and creates an `AgentRunCommand`.
2. Java issues a short-lived JWT for the Python Agent and Java Business Tool audiences whose claims exactly match the request access context.
3. Java creates an idempotent Python run over `POST /internal/v1/agent-runs`.
4. Java persists the canonical user message, consumes the Python SSE stream and translates terminal and delta events to the existing browser event model.
5. The knowledge graph invokes `knowledge_base.list`; Java verifies the Business Tool audience and returns only active resources inside the delegated scope.
6. Python queries only those returned vector collections, creates LlamaIndex evidence nodes and asks the configured LangChain chat adapter for a grounded answer.
7. Python emits source, answer and terminal events; Java persists the assistant answer, thinking text, sources, reply linkage and message status before publishing the terminal browser event.
8. Browser cancellation is forwarded to the Python cancellation endpoint with the accepted Python `runId`.

The administrator-only `GET /admin/agent/dashboard-analysis` flow is the second enabled Agent slice. Java resolves `dashboard:read` only for the `admin` role, Python calls the allowlisted `dashboard.overview` Tool with a bounded seven-day window, and LangChain generates an analysis from the returned structured dashboard values. Python never reads the dashboard tables directly, and Java rejects callers without the delegated scope before creating a run. This path shares the current `RAGENT_AGENT_RAG_ENABLED` and chat-model deployment configuration but does not execute pgvector retrieval.

Transport, JWT creation, SSE parsing, timeouts and Python problem details stay inside the Python `AgentGateway` adapter. A run exceeding `RAGENT_AGENT_PYTHON_RUN_TIMEOUT` is marked as `agent_run_timeout`, any partial answer is persisted as interrupted, and Java sends an idempotent cancellation to Python.

## Operations and release gates

- `/internal/v1/health` is a liveness probe. `/internal/v1/ready` returns 200 only when RAG is enabled and all adapters have deployable configuration.
- `/internal/v1/metrics` exposes `ragent_agent_runs_total`, `ragent_agent_active_runs` and `ragent_agent_run_duration_seconds`. Labels contain only Agent type and terminal status.
- Tool authorization failures, Tool timeout/unavailability, retrieval failures and language-model failures have stable problem codes. Provider exception details remain in server-side exception chains and are not returned to callers.
- The versioned golden set is `agent-service/evals/golden-rag-v1.jsonl`. Run the gate with `python -m ragent_agent.rag.evaluation --cases ... --observations ...`; a failed threshold returns a non-zero exit code.
- The legacy Java adapter remains the rollback target. Enable Python first in an isolated Java canary deployment, not by switching all Java instances at once.

## Current rollout limit

Phase 6 implements the first grounded knowledge flow against the existing pgvector schema. It remains disabled by default and supports only the knowledge Agent. The current source mapping uses vector metadata (`doc_id`, optional `doc_name`, `source_type`, `source_location`); Python does not read Java document tables.

Production traffic stays on the legacy runtime until golden retrieval/citation cases, cancellation and timeout behavior, provider failure handling, approval resume, restart reconciliation and canary comparisons pass. Canonical Java conversation persistence is complete. In-flight write workflows still require explicit idempotency and recovery policy before production cutover, so this phase is not an unrestricted production switch.
