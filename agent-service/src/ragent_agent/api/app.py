from __future__ import annotations

import json
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Depends, FastAPI, Header, Path, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response, StreamingResponse

from ragent_agent.api.errors import ProblemError, problem_error_handler
from ragent_agent.config import AgentServiceSettings
from ragent_agent.contracts.models import (
    AgentEvent,
    AgentEventType,
    AgentRunAccepted,
    AgentRunRequest,
    ApprovalDecision,
    HealthResponse,
    Problem,
    RunStatus,
)
from ragent_agent.ingestion.text import (
    EmbeddingProviderError,
    LangChainLlamaIndexTextIngestionService,
    TextIngestionRequest,
    TextIngestionResult,
    TextIngestionService,
)
from ragent_agent.observability.metrics import PrometheusRunMetrics
from ragent_agent.runtime.coordinator import AgentRunCoordinator
from ragent_agent.runtime.graph import LangGraphAgentRuntime
from ragent_agent.runtime.store import create_run_store
from ragent_agent.security.delegated_token import DelegatedPrincipal, DelegatedTokenVerifier


def create_app(settings: AgentServiceSettings, text_ingestion: TextIngestionService | None = None) -> FastAPI:
    runtime = LangGraphAgentRuntime(settings)
    metrics = PrometheusRunMetrics()
    store = create_run_store(settings)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        try:
            await store.initialize()
            await runtime.initialize()
            yield
        finally:
            await runtime.aclose()
            await store.aclose()

    app = FastAPI(
        title="Ragent Internal Agent Service",
        version=settings.service_version,
        lifespan=lifespan,
    )
    app.add_exception_handler(ProblemError, problem_error_handler)
    app.add_exception_handler(RequestValidationError, validation_error_handler)
    app.state.run_store = store
    app.state.token_verifier = DelegatedTokenVerifier(settings)
    app.state.coordinator = AgentRunCoordinator(
        store=store,
        runtime=runtime,
        auto_start_runs=settings.auto_start_runs,
        metrics=metrics,
    )
    app.state.text_ingestion = text_ingestion or (
        LangChainLlamaIndexTextIngestionService(settings) if settings.ingestion_enabled else None
    )

    @app.get("/internal/v1/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        return HealthResponse(status="ready", version=settings.service_version)

    @app.get("/internal/v1/ready", response_model=None)
    async def ready() -> JSONResponse:
        problem = await runtime.readiness()
        if problem is not None:
            return JSONResponse(
                status_code=problem.status,
                content=problem.model_dump(by_alias=True, mode="json", exclude_none=True),
                media_type="application/problem+json",
            )
        health = HealthResponse(status="ready", version=settings.service_version)
        return JSONResponse(content=health.model_dump(by_alias=True, mode="json", exclude_none=True))

    @app.get("/internal/v1/metrics", include_in_schema=False)
    async def prometheus_metrics() -> Response:
        payload, content_type = metrics.render()
        return Response(content=payload, headers={"Content-Type": content_type})

    @app.post("/internal/v1/ingestion/text-chunks")
    async def compute_text_chunks(
        request: Request,
        command: TextIngestionRequest,
        principal: Annotated[DelegatedPrincipal, Depends(require_principal)],
    ) -> JSONResponse:
        if "ingestion:compute" not in principal.tool_scopes:
            raise ProblemError(403, "Forbidden", "missing_ingestion_compute_scope")
        service: TextIngestionService | None = request.app.state.text_ingestion
        if service is None:
            raise ProblemError(503, "Service Unavailable", "ingestion_runtime_not_configured")
        try:
            result: TextIngestionResult = await service.compute(command)
        except EmbeddingProviderError as error:
            raise ProblemError(502, "Bad Gateway", "embedding_provider_failed") from error
        except ValueError as error:
            raise ProblemError(400, "Bad Request", "invalid_ingestion_request", str(error)) from error
        return JSONResponse(
            content={
                "chunks": list(result.chunks),
                "timings": {
                    "parseDurationMs": result.parse_duration_ms,
                    "chunkDurationMs": result.chunk_duration_ms,
                    "embedDurationMs": result.embed_duration_ms,
                },
            }
        )

    @app.post("/internal/v1/agent-runs", status_code=202)
    async def create_agent_run(
        request: Request,
        command: AgentRunRequest,
        idempotency_key: Annotated[str, Depends(require_idempotency_key)],
        principal: Annotated[DelegatedPrincipal, Depends(require_principal)],
    ) -> JSONResponse:
        verifier: DelegatedTokenVerifier = request.app.state.token_verifier
        verifier.validate_access(principal, command.access)
        coordinator: AgentRunCoordinator = request.app.state.coordinator
        record, _ = await coordinator.create(command, principal, idempotency_key)
        accepted = AgentRunAccepted(
            runId=record.run_id,
            status=RunStatus.ACCEPTED,
            eventStreamUrl=f"/internal/v1/agent-runs/{record.run_id}/events",
        )
        return JSONResponse(status_code=202, content=accepted.model_dump(by_alias=True, mode="json"))

    @app.get("/internal/v1/agent-runs/{run_id}")
    async def get_agent_run(
        request: Request,
        run_id: Annotated[str, Path(min_length=1)],
        principal: Annotated[DelegatedPrincipal, Depends(require_principal)],
    ) -> JSONResponse:
        coordinator: AgentRunCoordinator = request.app.state.coordinator
        state = await coordinator.state(run_id, principal)
        return JSONResponse(content=state.model_dump(by_alias=True, mode="json", exclude_none=True))

    @app.get("/internal/v1/agent-runs/{run_id}/events")
    async def stream_agent_run_events(
        request: Request,
        run_id: Annotated[str, Path(min_length=1)],
        principal: Annotated[DelegatedPrincipal, Depends(require_principal)],
        last_event_id: Annotated[str | None, Header(alias="Last-Event-ID")] = None,
    ) -> StreamingResponse:
        coordinator: AgentRunCoordinator = request.app.state.coordinator
        after_sequence = parse_last_event_id(last_event_id)
        await coordinator.state(run_id, principal)

        async def event_stream() -> AsyncIterator[str]:
            queue, backlog, terminal = await coordinator.subscribe(run_id, principal, after_sequence)
            try:
                for event in backlog:
                    yield encode_sse(event)
                if terminal:
                    return
                while True:
                    event = await queue.get()
                    yield encode_sse(event)
                    if event.type in TERMINAL_EVENT_TYPES:
                        return
            finally:
                await coordinator.unsubscribe(run_id, queue)

        return StreamingResponse(
            event_stream(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    @app.post("/internal/v1/agent-runs/{run_id}/cancel", status_code=202)
    async def cancel_agent_run(
        request: Request,
        run_id: Annotated[str, Path(min_length=1)],
        idempotency_key: Annotated[str, Depends(require_idempotency_key)],
        principal: Annotated[DelegatedPrincipal, Depends(require_principal)],
    ) -> JSONResponse:
        _ = idempotency_key
        coordinator: AgentRunCoordinator = request.app.state.coordinator
        state = await coordinator.cancel(run_id, principal)
        return JSONResponse(status_code=202, content=state.model_dump(by_alias=True, mode="json", exclude_none=True))

    @app.post("/internal/v1/agent-runs/{run_id}/resume", status_code=202)
    async def resume_agent_run(
        request: Request,
        run_id: Annotated[str, Path(min_length=1)],
        decision: ApprovalDecision,
        idempotency_key: Annotated[str, Depends(require_idempotency_key)],
        principal: Annotated[DelegatedPrincipal, Depends(require_principal)],
    ) -> JSONResponse:
        _ = idempotency_key
        coordinator: AgentRunCoordinator = request.app.state.coordinator
        state = await coordinator.resume(run_id, principal, decision, principal.authorization, idempotency_key)
        return JSONResponse(status_code=202, content=state.model_dump(by_alias=True, mode="json", exclude_none=True))

    return app


TERMINAL_EVENT_TYPES = {
    AgentEventType.RUN_COMPLETED,
    AgentEventType.RUN_FAILED,
    AgentEventType.RUN_CANCELLED,
}


async def require_principal(
    request: Request,
    authorization: Annotated[str | None, Header()] = None,
) -> DelegatedPrincipal:
    verifier: DelegatedTokenVerifier = request.app.state.token_verifier
    return verifier.verify(authorization)


async def require_idempotency_key(
    value: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
) -> str:
    if value is None or not 16 <= len(value) <= 128:
        raise ProblemError(400, "Bad Request", "invalid_idempotency_key")
    return value


async def validation_error_handler(request: Request, error: RequestValidationError) -> JSONResponse:
    problem = Problem(
        title="Bad Request",
        status=400,
        code="invalid_request",
        detail=str(error.errors()),
        traceId=request.headers.get("traceparent"),
    )
    return JSONResponse(
        status_code=400,
        content=problem.model_dump(by_alias=True, mode="json", exclude_none=True),
        media_type="application/problem+json",
    )


def parse_last_event_id(value: str | None) -> int:
    if value is None:
        return -1
    try:
        sequence = int(value)
    except ValueError as error:
        raise ProblemError(400, "Bad Request", "invalid_last_event_id") from error
    if sequence < -1:
        raise ProblemError(400, "Bad Request", "invalid_last_event_id")
    return sequence


def encode_sse(event: AgentEvent) -> str:
    data = json.dumps(event.model_dump(by_alias=True, mode="json"), ensure_ascii=False, separators=(",", ":"))
    return f"id: {event.sequence}\nevent: {event.type}\ndata: {data}\n\n"
