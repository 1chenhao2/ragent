# Java-Python Agent Migration Baseline

Status: proposed baseline for the strangler migration.

## Goal

Keep Java as the enterprise business control plane and make Python the only AI runtime. The migration must preserve the current public Java HTTP/SSE interface until the frontend is deliberately versioned.

## Verified current topology

- `frontend` calls the Spring Boot `bootstrap` application over HTTP and SSE.
- `bootstrap` owns authentication, users, knowledge metadata, conversations, audit, schedules, MQ, file storage, RAG orchestration and administration APIs.
- `framework` and `infra-ai` are Java libraries, not separately deployed services.
- `mcp-server` is a separate Java process containing demo tools and a You.com search adapter.
- PostgreSQL is the system database and pgvector is the default vector backend. Redis, RocketMQ and S3/OSS are supporting infrastructure.
- There is no API gateway implementation in this repository.

## Ownership decision

| Capability | Owner after migration |
| --- | --- |
| Authentication, users, roles and delegated identity | Java |
| Business rules, transactions and business APIs | Java |
| Knowledge base and document metadata | Java |
| Canonical conversations, messages, feedback and audit | Java |
| File storage, schedules and MQ publication | Java |
| Agent planning and graph execution | Python / LangGraph |
| Prompt rendering and model calls | Python / LangChain |
| Tool selection and structured arguments | Python / LangChain |
| RAG ingestion and retrieval | Python / LlamaIndex |
| Agent checkpoints and resumable state | Python / LangGraph checkpointer |
| Business tool implementation and final authorization | Java |

Python must not connect directly to Java business tables. It receives scoped identity from Java and invokes authorized internal tools.

## External seams

### Browser to Java

The existing `/rag/v3/chat` SSE behavior remains the public compatibility seam during migration. Java continues to authenticate the user and translate internal Agent events to the existing frontend events.

### Java to Python

The versioned contract is `docs/contracts/agent-service-v1.openapi.yaml`. A Java `AgentGateway` module will hide HTTP, SSE, retries and Python deployment details from controllers and business services.

### Python to Java tools

Python invokes allowlisted internal Java tools with a short-lived delegated token. Java rechecks tenant, user, role, resource scope and operation permission on every call. Model-provided identifiers are never trusted as identity.

### Asynchronous ingestion

Java owns document metadata and publishes ingestion requests. Python executes parsing, chunking, enrichment, embedding and indexing, then reports idempotent task and node status events.

## Runtime rules

- `runId`, `requestId`, `traceparent` and idempotency keys propagate end to end.
- The Java user token is not forwarded. Java issues an audience-restricted, short-lived internal token.
- Stateful tool calls require `toolCallId` idempotency and are not retried unless the tool declares that behavior safe.
- LangGraph state is stored in a dedicated PostgreSQL schema or database, separate from Java business tables.
- Vector queries always include tenant, knowledge-base and ACL filters.
- Java retains a legacy Agent adapter until Python passes quality gates and rollback drills.

## Quality gates

1. Contract tests pass for create, stream, cancel, approval resume and error mapping.
2. Existing SSE event order and terminal behavior remain compatible.
3. Golden RAG cases meet agreed retrieval, citation and grounded-answer thresholds.
4. Read-only tools match legacy behavior; write tools pass authorization, idempotency and approval tests.
5. Cancellation, timeout, provider failure and Python restart recovery are verified.
6. Shadow traffic shows acceptable latency, token cost and failure-rate deltas.
7. Secrets are supplied by environment or a secret manager and never committed.

## Test seams

Tests should observe behavior only through these interfaces:

- Existing Java chat/SSE interface.
- Java `AgentGateway` interface using production HTTP and in-memory adapters.
- Python Agent run contract.
- Java internal business-tool interface.
- Ingestion command and task-status interface.

Internal LangGraph nodes and legacy Java pipeline classes are not long-term test surfaces. Existing implementation-coupled tests are removed only after replacement contract coverage is green.

## First vertical slice

Implement one knowledge-base question flow behind a disabled-by-default feature flag:

1. Java authenticates and creates a scoped Agent run.
2. Python loads permitted context, performs retrieval and streams grounded output.
3. Java maps Agent events to the existing SSE format and persists the final message.
4. The same request can run against the legacy adapter for shadow comparison.

No business write tool, ingestion migration or legacy deletion belongs in this first slice.
