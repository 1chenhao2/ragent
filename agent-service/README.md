# Ragent Agent Service

FastAPI service that hosts the Python Agent runtime. Java remains the business control plane and calls this service through the versioned internal contract in `../docs/contracts/agent-service-v1.openapi.yaml`.

## Responsibilities

- Exposes the versioned internal Agent Run API and SSE event stream.
- Verifies short-lived delegated identity before accepting scoped access data.
- Owns Agent execution state without connecting to Java business tables.
- Uses LangGraph for workflow state, LangChain Core for model messages, and LlamaIndex Core for the RAG stage.
- Exposes separate liveness/readiness probes and low-cardinality Prometheus run metrics.

## Local Development

```powershell
$env:RAGENT_AGENT_DELEGATED_TOKEN_HMAC_SECRET="replace-with-at-least-32-characters"
$env:RAGENT_AGENT_DELEGATED_TOKEN_ISSUER="ragent-java"
$env:RAGENT_AGENT_DELEGATED_TOKEN_AUDIENCE="ragent-agent-service"
$env:RAGENT_AGENT_BUSINESS_TOOL_BASE_URL="http://127.0.0.1:8080"
$env:RAGENT_AGENT_RUN_STORE_BACKEND="postgres"
$env:RAGENT_AGENT_RUN_STORE_PG_DSN="postgresql://your_user:your_password@127.0.0.1:5432/ragent"
$env:RAGENT_AGENT_RUN_STORE_TIMEOUT_SECONDS="10"
$env:RAGENT_AGENT_CHECKPOINT_BACKEND="postgres"
$env:RAGENT_AGENT_CHECKPOINT_PG_DSN="postgresql://your_user:your_password@127.0.0.1:5432/ragent"
$env:RAGENT_AGENT_CHECKPOINT_TIMEOUT_SECONDS="10"
$env:RAGENT_AGENT_RAG_ENABLED="true"
$env:RAGENT_AGENT_RAG_PG_DSN="postgresql://your_user:your_password@127.0.0.1:5432/ragent"
$env:RAGENT_AGENT_RAG_EMBEDDING_BASE_URL="https://api.siliconflow.cn/v1"
$env:RAGENT_AGENT_RAG_EMBEDDING_API_KEY="<secret>"
$env:RAGENT_AGENT_RAG_EMBEDDING_MODEL="Qwen/Qwen3-Embedding-8B"
$env:RAGENT_AGENT_RAG_CHAT_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
$env:RAGENT_AGENT_RAG_CHAT_API_KEY="<secret>"
$env:RAGENT_AGENT_RAG_CHAT_MODEL="qwen-plus-latest"
$env:RAGENT_AGENT_INGESTION_ENABLED="false"
$env:RAGENT_AGENT_INGESTION_TIMEOUT_SECONDS="60"
$env:RAGENT_AGENT_INGESTION_EMBEDDING_MODEL_ID="qwen-emb-8b"
.\.venv\Scripts\python.exe -m uvicorn ragent_agent.main:app --host 127.0.0.1 --port 8000
```

`RAGENT_AGENT_RAG_ENABLED` defaults to `false`. Provider keys and database credentials must come from the deployment secret manager.
If delegated requests carry a non-null tenant ID, configure `RAGENT_AGENT_RAG_TENANT_METADATA_KEY` and populate that key in every vector row before enabling RAG.
`RAGENT_AGENT_RUN_STORE_BACKEND` defaults to `memory` for local development and tests. Production should use `postgres` with the schema from `../resources/database/upgrades/v1.1.0/260810_agent_runtime.sql` and a dedicated write-capable runtime role. The run store keeps run state, idempotency records and SSE events, but never stores delegated authorization tokens.

## Runtime Status

The knowledge Agent executes this LangGraph path when RAG is enabled:

1. Invoke the read-only Java `knowledge_base.list` Tool with the delegated token.
2. Restrict retrieval to the returned `collectionName` values.
3. Query the existing `t_knowledge_vector` pgvector index through the read-only asyncpg adapter.
4. Represent evidence as LlamaIndex nodes and build document-level source references.
5. Generate a grounded answer through LangChain's OpenAI-compatible chat adapter.
6. Emit `sources`, `answer_delta` and one `run_completed` event.

The pgvector adapter reads only the vector infrastructure table and never queries Java-owned knowledge, user or conversation tables. Unsupported Agent types and incomplete RAG configuration fail closed.
Use a dedicated PostgreSQL role with `SELECT` permission only on `t_knowledge_vector`; the adapter also opens database sessions with `default_transaction_read_only=on` and a command timeout.

## Data Analysis Agent

The `data_analysis` Agent is a separate LangGraph path for administrators. It invokes the Java-owned `dashboard.overview` Tool with a bounded seven-day window, then uses the configured LangChain chat model to summarize only the returned dashboard data. It does not query pgvector or connect to Java business tables. It currently shares `RAGENT_AGENT_RAG_ENABLED` and the chat-model configuration as its deployment gate.

Java exposes this capability through `GET /admin/agent/dashboard-analysis?question=...`. The Java control plane grants `dashboard:read` only to an authenticated `admin` role, validates every Tool invocation again, and limits a Tool-supplied window to 1-720 hours or 1-90 days. Non-administrators are rejected before an Agent run is created.

## Verification and rollout

```powershell
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m ruff check src tests
.\.venv\Scripts\python.exe -m ragent_agent.rag.evaluation `
  --cases evals/golden-rag-v1.jsonl `
  --observations tests/fixtures/golden-rag-v1-observations.jsonl
```

`/internal/v1/health` checks process liveness, `/internal/v1/ready` checks deployable RAG configuration, and `/internal/v1/metrics` exposes Prometheus data. The checked-in observation file verifies the gate itself; production approval must use observations generated from the target pre-production index and configured model.

Production traffic must remain on the Java legacy adapter until golden RAG evaluation, provider failure, cancellation, restart-recovery and canary quality gates pass.

## Text Ingestion Trial

`POST /internal/v1/ingestion/text-chunks` is an internal, feature-gated computation endpoint for `text/plain`, `text/markdown` and `text/x-markdown`. It requires a Java-issued JWT with `ingestion:compute`, uses LlamaIndex to segment text and LangChain to create embeddings, and returns chunks without connecting to Java business tables or writing vectors.

Set `RAGENT_AGENT_INGESTION_ENABLED=true` only with matching Java `RAGENT_AGENT_PYTHON_TEXT_INGESTION_ENABLED=true`. The configured `RAGENT_AGENT_INGESTION_EMBEDDING_MODEL_ID` must match Java's selected knowledge-base embedding model. Java retains document metadata, storage, chunk IDs, transaction handling, `t_knowledge_chunk` and `t_knowledge_vector` writes. With Java fallback enabled, only `agent_service_unavailable` and `embedding_provider_failed` return to the existing text path; invalid input and invalid Agent responses fail closed.

Before enabling either ingestion flag in a canary environment, validate the configured Embedding provider without accessing PostgreSQL, Java business tables or the vector index:

```powershell
.\.venv\Scripts\python.exe -m ragent_agent.rag.canary
```

The command requires `RAGENT_AGENT_RAG_EMBEDDING_BASE_URL` and `RAGENT_AGENT_RAG_EMBEDDING_API_KEY`. It emits the configured model identifiers, the actual returned dimension and request duration. Do not enable ingestion unless the command returns dimension `1536` and the model identifier matches Java's configured knowledge-base model.

## Restart behavior

When the PostgreSQL-backed service starts, active `running` records are reconciled to terminal `failed` records with code `agent_service_restarted`. `waiting_approval` records remain resumable because their LangGraph checkpoint has not crossed an executing side-effect boundary. A single `run_failed` event is appended only for interrupted execution, so Java can query the final state and replay the retained SSE event backlog after the restart.

The PostgreSQL run store and LangGraph checkpoint are separate persistence concerns. Configure `RAGENT_AGENT_CHECKPOINT_BACKEND=postgres` and the dedicated checkpoint DSN to retain workflow state. Delegated tokens are intentionally not persisted; Java must issue a fresh token for every approval resume. Runs that were actively executing during a process restart still fail closed instead of replaying unknown side effects.
