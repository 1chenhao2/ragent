# Ragent Agent Service

FastAPI service that hosts the Python Agent runtime. Java remains the business control plane and calls this service through the versioned internal contract in `../docs/contracts/agent-service-v1.openapi.yaml`.

## Responsibilities

- Exposes the versioned internal Agent Run API and SSE event stream.
- Verifies short-lived delegated identity before accepting scoped access data.
- Owns Agent execution state without connecting to Java business tables.
- Uses LangGraph for workflow state, LangChain Core for model messages, and LlamaIndex Core for the RAG stage.

## Local Development

```powershell
$env:RAGENT_AGENT_DELEGATED_TOKEN_HMAC_SECRET="replace-with-at-least-32-characters"
$env:RAGENT_AGENT_DELEGATED_TOKEN_ISSUER="ragent-java"
$env:RAGENT_AGENT_DELEGATED_TOKEN_AUDIENCE="ragent-agent-service"
.\.venv\Scripts\python.exe -m uvicorn ragent_agent.main:app --host 127.0.0.1 --port 8000
```

## Runtime Status

The current graph validates the runtime path and returns an explicit `agent_runtime_not_configured` terminal event.
This prevents placeholder chat behavior from being enabled before business tools and RAG retrieval are implemented.

Production traffic must remain on the Java legacy adapter until the later migration quality gates pass.
