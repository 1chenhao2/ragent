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
RAGENT_AGENT_PYTHON_DELEGATED_TOKEN_SECRET=<secret-from-secret-manager>
RAGENT_AGENT_PYTHON_DELEGATED_TOKEN_ISSUER=ragent-java
RAGENT_AGENT_PYTHON_DELEGATED_TOKEN_AUDIENCE=ragent-agent-service
RAGENT_AGENT_PYTHON_DELEGATED_TOKEN_TTL=5m
```

The Python process must use matching delegated-token settings:

```text
RAGENT_AGENT_DELEGATED_TOKEN_HMAC_SECRET=<same-secret-from-secret-manager>
RAGENT_AGENT_DELEGATED_TOKEN_ISSUER=ragent-java
RAGENT_AGENT_DELEGATED_TOKEN_AUDIENCE=ragent-agent-service
```

The HMAC secret must contain at least 32 characters and must not be committed. Switching `RAGENT_AGENT_RUNTIME` back to `legacy`, or removing it, restores the Java adapter.

## Request flow

1. Java authenticates the browser request and creates an `AgentRunCommand`.
2. Java issues a short-lived, audience-restricted JWT whose claims exactly match the request access context.
3. Java creates an idempotent Python run over `POST /internal/v1/agent-runs`.
4. Java consumes the Python SSE stream and translates terminal and delta events to the existing browser event model.
5. Browser cancellation is forwarded to the Python cancellation endpoint with the accepted Python `runId`.

Transport, JWT creation, SSE parsing, timeouts and Python problem details stay inside the Python `AgentGateway` adapter.

## Current rollout limit

Phase 4 deliberately delegates `knowledge:read` but no knowledge-base IDs. Phase 5 must resolve the authenticated user's allowed knowledge bases in Java and expose authorized business Tools before Python traffic can pass the quality gate. The Python graph also remains fail-closed with `agent_runtime_not_configured` until those Tools and RAG capabilities are installed.
