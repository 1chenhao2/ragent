from __future__ import annotations

import asyncio
import hashlib
import json
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from ragent_agent.api.errors import ProblemError
from ragent_agent.config import AgentServiceSettings
from ragent_agent.contracts.models import AgentEvent, AgentEventType, AgentRunRequest, AgentRunState, Problem, RunStatus
from ragent_agent.security.delegated_token import DelegatedPrincipal


@dataclass
class RunRecord:
    run_id: str
    owner_user_id: str
    owner_tenant_id: str | None
    request: AgentRunRequest
    delegated_authorization: str = field(repr=False)
    request_fingerprint: str
    status: RunStatus
    updated_at: datetime
    error: Problem | None = None
    waiting_approval_id: str | None = None
    events: list[AgentEvent] = field(default_factory=list)
    subscribers: set[asyncio.Queue[AgentEvent]] = field(default_factory=set)

    @property
    def terminal(self) -> bool:
        return self.status in {RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED}


class RunStore:
    def __init__(self) -> None:
        self._lock = asyncio.Lock()
        self._runs: dict[str, RunRecord] = {}
        self._idempotency: dict[tuple[str | None, str, str], str] = {}
        self._approval_idempotency: dict[tuple[str | None, str, str], tuple[str, str]] = {}

    async def create_or_get(
        self,
        run_id: str,
        request: AgentRunRequest,
        principal: DelegatedPrincipal,
        idempotency_key: str,
    ) -> tuple[RunRecord, bool]:
        fingerprint = self._request_fingerprint(request)
        key = (principal.tenant_id, principal.user_id, idempotency_key)
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
                owner_tenant_id=principal.tenant_id,
                request=request,
                delegated_authorization=principal.authorization,
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

    async def publish(self, run_id: str, event_type: AgentEventType, data: dict[str, object]) -> bool:
        async with self._lock:
            record = self._runs.get(run_id)
            if record is None:
                raise ProblemError(404, "Not Found", "run_not_found")
            if record.terminal:
                return False
            record.updated_at = self._now()
            self._append_event(record, event_type, data)
            return True

    async def complete(self, run_id: str, data: dict[str, object]) -> AgentRunState:
        async with self._lock:
            record = self._runs.get(run_id)
            if record is None:
                raise ProblemError(404, "Not Found", "run_not_found")
            if not record.terminal:
                record.status = RunStatus.COMPLETED
                record.waiting_approval_id = None
                record.updated_at = self._now()
                self._append_event(record, AgentEventType.RUN_COMPLETED, data)
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

    async def wait_for_approval(self, run_id: str, approval_id: str) -> AgentRunState:
        async with self._lock:
            record = self._runs.get(run_id)
            if record is None:
                raise ProblemError(404, "Not Found", "run_not_found")
            if record.terminal:
                return self._to_state(record)
            record.status = RunStatus.WAITING_APPROVAL
            record.waiting_approval_id = approval_id
            record.updated_at = self._now()
            self._append_event(record, AgentEventType.APPROVAL_REQUIRED, {"approvalId": approval_id})
            return self._to_state(record)

    async def begin_resume(
        self,
        run_id: str,
        principal: DelegatedPrincipal,
        approval_id: str,
        idempotency_key: str | None = None,
        decision_fingerprint: str | None = None,
    ) -> tuple[RunRecord, bool]:
        async with self._lock:
            record = self._owned_record(run_id, principal)
            if idempotency_key is not None:
                key = (principal.tenant_id, principal.user_id, idempotency_key)
                fingerprint = decision_fingerprint or approval_id
                existing = self._approval_idempotency.get(key)
                if existing is not None:
                    if existing != (run_id, fingerprint):
                        raise ProblemError(409, "Conflict", "idempotency_conflict")
                    return record, False
            if record.status != RunStatus.WAITING_APPROVAL:
                raise ProblemError(409, "Conflict", "approval_not_pending")
            if record.waiting_approval_id != approval_id:
                raise ProblemError(409, "Conflict", "approval_id_mismatch")
            access = record.request.access
            if access.user_id != principal.user_id:
                raise ProblemError(403, "Forbidden", "resume_user_mismatch")
            if access.tenant_id != principal.tenant_id:
                raise ProblemError(403, "Forbidden", "resume_tenant_mismatch")
            if not set(access.roles).issubset(principal.roles):
                raise ProblemError(403, "Forbidden", "resume_role_mismatch")
            if not set(access.allowed_knowledge_base_ids).issubset(principal.allowed_knowledge_base_ids):
                raise ProblemError(403, "Forbidden", "resume_knowledge_base_mismatch")
            if not set(access.tool_scopes).issubset(principal.tool_scopes):
                raise ProblemError(403, "Forbidden", "resume_tool_scope_mismatch")
            record.status = RunStatus.RUNNING
            record.waiting_approval_id = None
            record.updated_at = self._now()
            if idempotency_key is not None:
                self._approval_idempotency[(principal.tenant_id, principal.user_id, idempotency_key)] = (
                    run_id,
                    decision_fingerprint or approval_id,
                )
            return record, True

    async def initialize(self) -> None:
        return None

    async def aclose(self) -> None:
        return None

    async def reconcile_after_restart(self) -> None:
        restart_error = Problem(
            title="Agent service restarted",
            status=503,
            code="agent_service_restarted",
            detail="The Agent run was interrupted by an Agent service restart.",
        )
        async with self._lock:
            for record in self._runs.values():
                if record.terminal:
                    continue
                record.status = RunStatus.FAILED
                record.error = restart_error
                record.updated_at = self._now()
                self._append_event(
                    record,
                    AgentEventType.RUN_FAILED,
                    {"error": restart_error.model_dump(by_alias=True, mode="json", exclude_none=True)},
                )

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
        if record.owner_user_id != principal.user_id or record.owner_tenant_id != principal.tenant_id:
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
            waitingApprovalId=record.waiting_approval_id,
            updatedAt=record.updated_at,
            error=record.error,
        )


class PostgresRunStore(RunStore):
    """Durable run/event adapter with restart reconciliation.

    Delegated authorization is deliberately kept out of PostgreSQL. Runs that
    were active during a process restart are terminalized with a stable error
    instead of attempting to replay side effects with an expired token.
    """

    _SCHEMA = "ragent_agent"

    def __init__(self, dsn: str, timeout_seconds: float) -> None:
        super().__init__()
        self._dsn = dsn
        self._timeout_seconds = timeout_seconds
        self._pool: Any = None
        self._persistence_lock = asyncio.Lock()

    async def initialize(self) -> None:
        try:
            import asyncpg
        except ImportError as error:
            raise RuntimeError("The asyncpg dependency is required for PostgreSQL run storage") from error
        self._pool = await asyncpg.create_pool(
            dsn=self._dsn,
            min_size=1,
            max_size=5,
            command_timeout=self._timeout_seconds,
            server_settings={
                "application_name": "ragent-agent-service",
                "default_transaction_read_only": "off",
            },
        )
        async with self._pool.acquire() as connection:
            await connection.execute(
                """
                CREATE SCHEMA IF NOT EXISTS ragent_agent;
                CREATE TABLE IF NOT EXISTS ragent_agent.runs (
                    run_id TEXT PRIMARY KEY,
                    tenant_id TEXT,
                    user_id TEXT NOT NULL,
                    request JSONB NOT NULL,
                    request_fingerprint TEXT NOT NULL,
                    status TEXT NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL,
                    waiting_approval_id TEXT,
                    error JSONB
                );
                ALTER TABLE ragent_agent.runs
                    ADD COLUMN IF NOT EXISTS waiting_approval_id TEXT;
                CREATE INDEX IF NOT EXISTS idx_ragent_agent_runs_active
                    ON ragent_agent.runs (status, updated_at)
                    WHERE status IN ('running', 'waiting_approval');
                CREATE TABLE IF NOT EXISTS ragent_agent.idempotency (
                    tenant_key TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    run_id TEXT NOT NULL REFERENCES ragent_agent.runs(run_id) ON DELETE CASCADE,
                    request_fingerprint TEXT NOT NULL,
                    PRIMARY KEY (tenant_key, user_id, idempotency_key)
                );
                CREATE TABLE IF NOT EXISTS ragent_agent.events (
                    run_id TEXT NOT NULL REFERENCES ragent_agent.runs(run_id) ON DELETE CASCADE,
                    sequence INTEGER NOT NULL,
                    event_type TEXT NOT NULL,
                    event_timestamp TIMESTAMPTZ NOT NULL,
                    data JSONB NOT NULL,
                    PRIMARY KEY (run_id, sequence)
                );
                CREATE TABLE IF NOT EXISTS ragent_agent.approval_idempotency (
                    tenant_key TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    run_id TEXT NOT NULL REFERENCES ragent_agent.runs(run_id) ON DELETE CASCADE,
                    decision_fingerprint TEXT NOT NULL,
                    PRIMARY KEY (tenant_key, user_id, idempotency_key)
                );
                """
            )
        await self._reconcile_database()
        await self._load_records()

    async def aclose(self) -> None:
        if self._pool is not None:
            await self._pool.close()
            self._pool = None

    async def create_or_get(
        self,
        run_id: str,
        request: AgentRunRequest,
        principal: DelegatedPrincipal,
        idempotency_key: str,
    ) -> tuple[RunRecord, bool]:
        pool = self._require_pool()
        fingerprint = self._request_fingerprint(request)
        tenant_key = principal.tenant_id or ""
        async with self._persistence_lock:
            async with pool.acquire() as connection, connection.transaction():
                existing = await connection.fetchrow(
                    """
                    SELECT run_id, request_fingerprint
                    FROM ragent_agent.idempotency
                    WHERE tenant_key = $1 AND user_id = $2 AND idempotency_key = $3
                    FOR UPDATE
                    """,
                    tenant_key,
                    principal.user_id,
                    idempotency_key,
                )
                if existing is not None:
                    if existing["request_fingerprint"] != fingerprint:
                        raise ProblemError(409, "Conflict", "idempotency_conflict")
                    record = await self._record_from_database(connection, existing["run_id"])
                    await self._register_record(record)
                    return record, False

                now = self._now()
                record = RunRecord(
                    run_id=run_id,
                    owner_user_id=principal.user_id,
                    owner_tenant_id=principal.tenant_id,
                    request=request,
                    delegated_authorization=principal.authorization,
                    request_fingerprint=fingerprint,
                    status=RunStatus.RUNNING,
                    updated_at=now,
                )
                self._append_event(
                    record,
                    AgentEventType.RUN_STARTED,
                    {
                        "conversationId": request.conversation_id,
                        "agentType": request.agent_type,
                    },
                )
                await self._write_record(connection, record)
                inserted = await connection.fetchrow(
                    """
                    INSERT INTO ragent_agent.idempotency
                        (tenant_key, user_id, idempotency_key, run_id, request_fingerprint)
                    VALUES ($1, $2, $3, $4, $5)
                    ON CONFLICT (tenant_key, user_id, idempotency_key) DO NOTHING
                    RETURNING run_id
                    """,
                    tenant_key,
                    principal.user_id,
                    idempotency_key,
                    run_id,
                    fingerprint,
                )
                if inserted is None:
                    await connection.execute("DELETE FROM ragent_agent.runs WHERE run_id = $1", run_id)
                    existing = await connection.fetchrow(
                        """
                        SELECT run_id, request_fingerprint
                        FROM ragent_agent.idempotency
                        WHERE tenant_key = $1 AND user_id = $2 AND idempotency_key = $3
                        """,
                        tenant_key,
                        principal.user_id,
                        idempotency_key,
                    )
                    if existing is None:
                        raise ProblemError(503, "Service Unavailable", "run_store_unavailable")
                    if existing["request_fingerprint"] != fingerprint:
                        raise ProblemError(409, "Conflict", "idempotency_conflict")
                    record = await self._record_from_database(connection, existing["run_id"])
                    created = False
                else:
                    created = True
            await self._register_record(record)
            return record, created

    async def cancel(self, run_id: str, principal: DelegatedPrincipal) -> AgentRunState:
        state = await super().cancel(run_id, principal)
        await self._persist_current(run_id)
        return state

    async def fail(self, run_id: str, error: Problem) -> AgentRunState:
        state = await super().fail(run_id, error)
        await self._persist_current(run_id)
        return state

    async def publish(self, run_id: str, event_type: AgentEventType, data: dict[str, object]) -> bool:
        published = await super().publish(run_id, event_type, data)
        if published:
            await self._persist_current(run_id)
        return published

    async def complete(self, run_id: str, data: dict[str, object]) -> AgentRunState:
        state = await super().complete(run_id, data)
        await self._persist_current(run_id)
        return state

    async def wait_for_approval(self, run_id: str, approval_id: str) -> AgentRunState:
        state = await super().wait_for_approval(run_id, approval_id)
        await self._persist_current(run_id)
        return state

    async def begin_resume(
        self,
        run_id: str,
        principal: DelegatedPrincipal,
        approval_id: str,
        idempotency_key: str | None = None,
        decision_fingerprint: str | None = None,
    ) -> tuple[RunRecord, bool]:
        if idempotency_key is None:
            record, started = await super().begin_resume(run_id, principal, approval_id)
            await self._persist_current(run_id)
            return record, started

        fingerprint = decision_fingerprint or approval_id
        tenant_key = principal.tenant_id or ""
        async with self._persistence_lock:
            pool = self._require_pool()
            async with pool.acquire() as connection, connection.transaction():
                existing = await connection.fetchrow(
                    """
                    SELECT run_id, decision_fingerprint
                    FROM ragent_agent.approval_idempotency
                    WHERE tenant_key = $1 AND user_id = $2 AND idempotency_key = $3
                    FOR UPDATE
                    """,
                    tenant_key,
                    principal.user_id,
                    idempotency_key,
                )
                if existing is not None:
                    if existing["run_id"] != run_id or existing["decision_fingerprint"] != fingerprint:
                        raise ProblemError(409, "Conflict", "idempotency_conflict")
                    record = await self._record_from_database(connection, run_id)
                    self._require_owner(record, principal)
                    await self._replace_record(record)
                    return record, False

                record, _ = await super().begin_resume(run_id, principal, approval_id)
                await self._write_record(connection, record)
                inserted = await connection.fetchrow(
                    """
                    INSERT INTO ragent_agent.approval_idempotency
                        (tenant_key, user_id, idempotency_key, run_id, decision_fingerprint)
                    VALUES ($1, $2, $3, $4, $5)
                    ON CONFLICT (tenant_key, user_id, idempotency_key) DO NOTHING
                    RETURNING run_id
                    """,
                    tenant_key,
                    principal.user_id,
                    idempotency_key,
                    run_id,
                    fingerprint,
                )
                if inserted is None:
                    existing = await connection.fetchrow(
                        """
                        SELECT run_id, decision_fingerprint
                        FROM ragent_agent.approval_idempotency
                        WHERE tenant_key = $1 AND user_id = $2 AND idempotency_key = $3
                        """,
                        tenant_key,
                        principal.user_id,
                        idempotency_key,
                    )
                    if existing is None or existing["run_id"] != run_id or existing["decision_fingerprint"] != fingerprint:
                        raise ProblemError(409, "Conflict", "idempotency_conflict")
                    record = await self._record_from_database(connection, run_id)
                    await self._replace_record(record)
                    return record, False
                self._approval_idempotency[(principal.tenant_id, principal.user_id, idempotency_key)] = (run_id, fingerprint)
                return record, True

    async def reconcile_after_restart(self) -> None:
        await super().reconcile_after_restart()
        await self._persist_all()

    def _require_pool(self) -> Any:
        if self._pool is None:
            raise RuntimeError("PostgreSQL run store has not been initialized")
        return self._pool

    async def _reconcile_database(self) -> None:
        pool = self._require_pool()
        error = Problem(
            title="Agent service restarted",
            status=503,
            code="agent_service_restarted",
            detail="The Agent run was interrupted by an Agent service restart.",
        )
        async with pool.acquire() as connection, connection.transaction():
            rows = await connection.fetch(
                """
                SELECT run_id
                FROM ragent_agent.runs
                WHERE status = 'running'
                FOR UPDATE
                """
            )
            for row in rows:
                await connection.execute(
                    """
                    UPDATE ragent_agent.runs
                    SET status = 'failed', updated_at = $2, error = $3::jsonb
                    WHERE run_id = $1
                    """,
                    row["run_id"],
                    self._now(),
                    json.dumps(error.model_dump(by_alias=True, mode="json", exclude_none=True)),
                )
                await connection.execute(
                    """
                    INSERT INTO ragent_agent.events
                        (run_id, sequence, event_type, event_timestamp, data)
                    SELECT $1, COALESCE(MAX(sequence) + 1, 0), 'run_failed', $2, $3::jsonb
                    FROM ragent_agent.events
                    WHERE run_id = $1
                    ON CONFLICT (run_id, sequence) DO NOTHING
                    """,
                    row["run_id"],
                    self._now(),
                    json.dumps({"error": error.model_dump(by_alias=True, mode="json", exclude_none=True)}),
                )

    async def _load_records(self) -> None:
        pool = self._require_pool()
        async with pool.acquire() as connection:
            run_rows = await connection.fetch("SELECT * FROM ragent_agent.runs ORDER BY updated_at, run_id")
            event_rows = await connection.fetch(
                """
                SELECT run_id, sequence, event_type, event_timestamp, data
                FROM ragent_agent.events
                ORDER BY run_id, sequence
                """
            )
            idempotency_rows = await connection.fetch("SELECT * FROM ragent_agent.idempotency")
            approval_idempotency_rows = await connection.fetch("SELECT * FROM ragent_agent.approval_idempotency")

        events_by_run: dict[str, list[AgentEvent]] = {}
        for row in event_rows:
            events_by_run.setdefault(row["run_id"], []).append(
                AgentEvent(
                    runId=row["run_id"],
                    sequence=row["sequence"],
                    type=AgentEventType(row["event_type"]),
                    timestamp=row["event_timestamp"],
                    data=_json_object(row["data"]),
                )
            )

        async with self._lock:
            self._runs.clear()
            self._idempotency.clear()
            self._approval_idempotency.clear()
            for row in run_rows:
                error = row["error"]
                record = RunRecord(
                    run_id=row["run_id"],
                    owner_user_id=row["user_id"],
                    owner_tenant_id=row["tenant_id"],
                    request=AgentRunRequest.model_validate(_json_object(row["request"])),
                    delegated_authorization="",
                    request_fingerprint=row["request_fingerprint"],
                    status=RunStatus(row["status"]),
                    waiting_approval_id=row["waiting_approval_id"],
                    updated_at=row["updated_at"],
                    error=Problem.model_validate(_json_object(error)) if error is not None else None,
                    events=events_by_run.get(row["run_id"], []),
                )
                self._runs[record.run_id] = record
            for row in idempotency_rows:
                self._idempotency[(row["tenant_key"] or None, row["user_id"], row["idempotency_key"])] = row[
                    "run_id"
                ]
            for row in approval_idempotency_rows:
                self._approval_idempotency[
                    (row["tenant_key"] or None, row["user_id"], row["idempotency_key"])
                ] = (row["run_id"], row["decision_fingerprint"])

    async def _record_from_database(self, connection: Any, run_id: str) -> RunRecord:
        row = await connection.fetchrow("SELECT * FROM ragent_agent.runs WHERE run_id = $1", run_id)
        if row is None:
            raise ProblemError(503, "Service Unavailable", "run_store_unavailable")
        events = await connection.fetch(
            """
            SELECT run_id, sequence, event_type, event_timestamp, data
            FROM ragent_agent.events
            WHERE run_id = $1
            ORDER BY sequence
            """,
            run_id,
        )
        error = row["error"]
        return RunRecord(
            run_id=row["run_id"],
            owner_user_id=row["user_id"],
            owner_tenant_id=row["tenant_id"],
            request=AgentRunRequest.model_validate(_json_object(row["request"])),
            delegated_authorization="",
            request_fingerprint=row["request_fingerprint"],
            status=RunStatus(row["status"]),
            waiting_approval_id=row["waiting_approval_id"],
            updated_at=row["updated_at"],
            error=Problem.model_validate(_json_object(error)) if error is not None else None,
            events=[
                AgentEvent(
                    runId=event["run_id"],
                    sequence=event["sequence"],
                    type=AgentEventType(event["event_type"]),
                    timestamp=event["event_timestamp"],
                    data=_json_object(event["data"]),
                )
                for event in events
            ],
        )

    async def _register_record(self, record: RunRecord) -> None:
        async with self._lock:
            if record.run_id not in self._runs:
                self._runs[record.run_id] = record

    async def _replace_record(self, record: RunRecord) -> None:
        async with self._lock:
            self._runs[record.run_id] = record

    @staticmethod
    def _require_owner(record: RunRecord, principal: DelegatedPrincipal) -> None:
        if record.owner_user_id != principal.user_id or record.owner_tenant_id != principal.tenant_id:
            raise ProblemError(403, "Forbidden", "run_access_denied")

    async def _persist_current(self, run_id: str) -> None:
        async with self._persistence_lock:
            record = await super().record_internal(run_id)
            pool = self._require_pool()
            async with pool.acquire() as connection, connection.transaction():
                await self._write_record(connection, record)

    async def _persist_all(self) -> None:
        async with self._persistence_lock:
            pool = self._require_pool()
            async with pool.acquire() as connection, connection.transaction():
                async with self._lock:
                    records = tuple(self._runs.values())
                for record in records:
                    await self._write_record(connection, record)

    async def _write_record(self, connection: Any, record: RunRecord) -> None:
        error = (
            json.dumps(record.error.model_dump(by_alias=True, mode="json", exclude_none=True))
            if record.error is not None
            else None
        )
        await connection.execute(
            """
            INSERT INTO ragent_agent.runs
                (run_id, tenant_id, user_id, request, request_fingerprint, status, updated_at, waiting_approval_id, error)
            VALUES ($1, $2, $3, $4::jsonb, $5, $6, $7, $8, $9::jsonb)
            ON CONFLICT (run_id) DO UPDATE SET
                tenant_id = EXCLUDED.tenant_id,
                user_id = EXCLUDED.user_id,
                request = EXCLUDED.request,
                request_fingerprint = EXCLUDED.request_fingerprint,
                status = EXCLUDED.status,
                updated_at = EXCLUDED.updated_at,
                waiting_approval_id = EXCLUDED.waiting_approval_id,
                error = EXCLUDED.error
            """,
            record.run_id,
            record.owner_tenant_id,
            record.owner_user_id,
            json.dumps(record.request.model_dump(by_alias=True, mode="json")),
            record.request_fingerprint,
            record.status.value,
            record.updated_at,
            record.waiting_approval_id,
            error,
        )
        for event in record.events:
            await connection.execute(
                """
                INSERT INTO ragent_agent.events
                    (run_id, sequence, event_type, event_timestamp, data)
                VALUES ($1, $2, $3, $4, $5::jsonb)
                ON CONFLICT (run_id, sequence) DO NOTHING
                """,
                record.run_id,
                event.sequence,
                event.type.value,
                event.timestamp,
                json.dumps(event.data),
            )


def create_run_store(settings: AgentServiceSettings) -> RunStore:
    backend = settings.run_store_backend.strip().lower()
    if backend == "memory":
        return RunStore()
    if backend == "postgres":
        if settings.run_store_pg_dsn is None or not settings.run_store_pg_dsn.strip():
            raise ValueError("RAGENT_AGENT_RUN_STORE_PG_DSN must be configured for PostgreSQL run storage")
        return PostgresRunStore(settings.run_store_pg_dsn, settings.run_store_timeout_seconds)
    raise ValueError(f"Unsupported RAGENT_AGENT_RUN_STORE_BACKEND: {settings.run_store_backend}")


def _json_object(value: Any) -> dict[str, Any]:
    if isinstance(value, str):
        value = json.loads(value)
    if not isinstance(value, dict):
        raise TypeError("Run store JSON value must be an object")
    return value
