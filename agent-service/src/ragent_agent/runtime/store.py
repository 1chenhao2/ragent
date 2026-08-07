from __future__ import annotations

import asyncio
import hashlib
import json
from dataclasses import dataclass, field
from datetime import UTC, datetime

from ragent_agent.api.errors import ProblemError
from ragent_agent.contracts.models import AgentEvent, AgentEventType, AgentRunRequest, AgentRunState, Problem, RunStatus
from ragent_agent.security.delegated_token import DelegatedPrincipal


@dataclass
class RunRecord:
    run_id: str
    owner_user_id: str
    request: AgentRunRequest
    request_fingerprint: str
    status: RunStatus
    updated_at: datetime
    error: Problem | None = None
    events: list[AgentEvent] = field(default_factory=list)
    subscribers: set[asyncio.Queue[AgentEvent]] = field(default_factory=set)

    @property
    def terminal(self) -> bool:
        return self.status in {RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED}


class RunStore:
    def __init__(self) -> None:
        self._lock = asyncio.Lock()
        self._runs: dict[str, RunRecord] = {}
        self._idempotency: dict[tuple[str, str], str] = {}

    async def create_or_get(
        self,
        run_id: str,
        request: AgentRunRequest,
        principal: DelegatedPrincipal,
        idempotency_key: str,
    ) -> tuple[RunRecord, bool]:
        fingerprint = self._request_fingerprint(request)
        key = (principal.user_id, idempotency_key)
        async with self._lock:
            existing_run_id = self._idempotency.get(key)
            if existing_run_id is not None:
                record = self._runs[existing_run_id]
                if record.request_fingerprint != fingerprint:
                    raise ProblemError(409, "Conflict", "idempotency_conflict")
                return record, False

            now = self._now()
            record = RunRecord(
                run_id=run_id,
                owner_user_id=principal.user_id,
                request=request,
                request_fingerprint=fingerprint,
                status=RunStatus.RUNNING,
                updated_at=now,
            )
            self._runs[run_id] = record
            self._idempotency[key] = run_id
            self._append_event(
                record,
                AgentEventType.RUN_STARTED,
                {
                    "conversationId": request.conversation_id,
                    "agentType": request.agent_type,
                },
            )
            return record, True

    async def state_for(self, run_id: str, principal: DelegatedPrincipal) -> AgentRunState:
        async with self._lock:
            return self._to_state(self._owned_record(run_id, principal))

    async def record_for(self, run_id: str, principal: DelegatedPrincipal) -> RunRecord:
        async with self._lock:
            return self._owned_record(run_id, principal)

    async def record_internal(self, run_id: str) -> RunRecord:
        async with self._lock:
            record = self._runs.get(run_id)
            if record is None:
                raise ProblemError(404, "Not Found", "run_not_found")
            return record

    async def cancel(self, run_id: str, principal: DelegatedPrincipal) -> AgentRunState:
        async with self._lock:
            record = self._owned_record(run_id, principal)
            if not record.terminal:
                record.status = RunStatus.CANCELLED
                record.updated_at = self._now()
                self._append_event(record, AgentEventType.RUN_CANCELLED, {})
            return self._to_state(record)

    async def fail(self, run_id: str, error: Problem) -> AgentRunState:
        async with self._lock:
            record = self._runs.get(run_id)
            if record is None:
                raise ProblemError(404, "Not Found", "run_not_found")
            if not record.terminal:
                record.status = RunStatus.FAILED
                record.error = error
                record.updated_at = self._now()
                self._append_event(
                    record,
                    AgentEventType.RUN_FAILED,
                    {
                        "error": error.model_dump(by_alias=True, mode="json", exclude_none=True),
                    },
                )
            return self._to_state(record)

    async def subscribe(
        self,
        run_id: str,
        principal: DelegatedPrincipal,
        after_sequence: int,
    ) -> tuple[asyncio.Queue[AgentEvent], list[AgentEvent], bool]:
        async with self._lock:
            record = self._owned_record(run_id, principal)
            queue: asyncio.Queue[AgentEvent] = asyncio.Queue()
            record.subscribers.add(queue)
            backlog = [event for event in record.events if event.sequence > after_sequence]
            return queue, backlog, record.terminal

    async def unsubscribe(self, run_id: str, queue: asyncio.Queue[AgentEvent]) -> None:
        async with self._lock:
            record = self._runs.get(run_id)
            if record is not None:
                record.subscribers.discard(queue)

    async def require_waiting_approval(self, run_id: str, principal: DelegatedPrincipal) -> AgentRunState:
        async with self._lock:
            record = self._owned_record(run_id, principal)
            if record.status != RunStatus.WAITING_APPROVAL:
                raise ProblemError(409, "Conflict", "approval_not_pending")
            return self._to_state(record)

    @staticmethod
    def _request_fingerprint(request: AgentRunRequest) -> str:
        content = json.dumps(
            request.model_dump(by_alias=True, mode="json"),
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        )
        return hashlib.sha256(content.encode("utf-8")).hexdigest()

    @staticmethod
    def _now() -> datetime:
        return datetime.now(UTC)

    def _owned_record(self, run_id: str, principal: DelegatedPrincipal) -> RunRecord:
        record = self._runs.get(run_id)
        if record is None:
            raise ProblemError(404, "Not Found", "run_not_found")
        if record.owner_user_id != principal.user_id:
            raise ProblemError(403, "Forbidden", "run_access_denied")
        return record

    def _append_event(self, record: RunRecord, event_type: AgentEventType, data: dict[str, object]) -> None:
        event = AgentEvent(
            runId=record.run_id,
            sequence=len(record.events),
            type=event_type,
            timestamp=self._now(),
            data=data,
        )
        record.events.append(event)
        for queue in record.subscribers:
            queue.put_nowait(event)

    @staticmethod
    def _to_state(record: RunRecord) -> AgentRunState:
        return AgentRunState(
            runId=record.run_id,
            status=record.status,
            updatedAt=record.updated_at,
            error=record.error,
        )
