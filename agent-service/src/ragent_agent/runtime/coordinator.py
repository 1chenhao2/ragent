from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import time
import uuid

from ragent_agent.contracts.models import AgentEventType, AgentRunRequest, AgentRunState, ApprovalDecision, Problem
from ragent_agent.observability.metrics import NoOpRunMetrics, RunMetrics
from ragent_agent.runtime.graph import LangGraphAgentRuntime
from ragent_agent.runtime.store import RunRecord, RunStore
from ragent_agent.security.delegated_token import DelegatedPrincipal

logger = logging.getLogger(__name__)


class AgentRunCoordinator:
    def __init__(
        self,
        store: RunStore,
        runtime: LangGraphAgentRuntime,
        auto_start_runs: bool,
        metrics: RunMetrics | None = None,
    ) -> None:
        self._store = store
        self._runtime = runtime
        self._auto_start_runs = auto_start_runs
        self._metrics = metrics or NoOpRunMetrics()
        self._tasks: dict[str, asyncio.Task[None]] = {}
        self._tasks_lock = asyncio.Lock()

    async def create(
        self,
        request: AgentRunRequest,
        principal: DelegatedPrincipal,
        idempotency_key: str,
    ) -> tuple[RunRecord, bool]:
        record, created = await self._store.create_or_get(
            run_id=uuid.uuid4().hex,
            request=request,
            principal=principal,
            idempotency_key=idempotency_key,
        )
        if created and self._auto_start_runs:
            task = asyncio.create_task(self._execute(record.run_id), name=f"agent-run-{record.run_id}")
            async with self._tasks_lock:
                self._tasks[record.run_id] = task
        return record, created

    async def state(self, run_id: str, principal: DelegatedPrincipal) -> AgentRunState:
        return await self._store.state_for(run_id, principal)

    async def cancel(self, run_id: str, principal: DelegatedPrincipal) -> AgentRunState:
        state = await self._store.cancel(run_id, principal)
        async with self._tasks_lock:
            task = self._tasks.get(run_id)
        if task is not None:
            task.cancel()
        return state

    async def resume(
        self,
        run_id: str,
        principal: DelegatedPrincipal,
        decision: ApprovalDecision,
        delegated_authorization: str | None = None,
        idempotency_key: str | None = None,
    ) -> AgentRunState:
        record, started = await self._store.begin_resume(
            run_id,
            principal,
            decision.approval_id,
            idempotency_key,
            self._approval_decision_fingerprint(decision),
        )
        if not started:
            return await self._store.state_for(run_id, principal)
        record.delegated_authorization = delegated_authorization or principal.authorization
        task = asyncio.create_task(
            self._resume(record, decision),
            name=f"agent-run-resume-{record.run_id}",
        )
        async with self._tasks_lock:
            self._tasks[record.run_id] = task
        return await self._store.state_for(run_id, principal)

    async def subscribe(self, run_id: str, principal: DelegatedPrincipal, after_sequence: int):
        return await self._store.subscribe(run_id, principal, after_sequence)

    async def unsubscribe(self, run_id: str, queue) -> None:
        await self._store.unsubscribe(run_id, queue)

    async def _execute(self, run_id: str) -> None:
        agent_type: str | None = None
        request_id: str | None = None
        terminal_status = "failed"
        started_at = time.perf_counter()
        try:
            record = await self._store.record_internal(run_id)
            agent_type = str(record.request.agent_type)
            request_id = record.request.request_id
            self._metrics.started(agent_type)
            outcome = await self._runtime.execute(record.request, run_id, record.delegated_authorization)
            if outcome.failure is not None:
                await self._store.fail(run_id, outcome.failure)
                return
            if outcome.waiting_approval_id is not None:
                await self._store.wait_for_approval(run_id, outcome.waiting_approval_id)
                terminal_status = "waiting_approval"
                return
            if outcome.answer is None:
                raise RuntimeError("Agent runtime returned neither an answer nor a failure")

            sources = list(outcome.sources)
            if sources and not await self._store.publish(
                run_id,
                AgentEventType.SOURCES,
                {"sources": sources},
            ):
                terminal_status = "cancelled"
                return
            if not await self._store.publish(
                run_id,
                AgentEventType.ANSWER_DELTA,
                {"delta": outcome.answer},
            ):
                terminal_status = "cancelled"
                return
            await self._store.complete(run_id, {"sources": sources})
            terminal_status = "completed"
        except asyncio.CancelledError:
            terminal_status = "cancelled"
            raise
        except Exception:
            logger.exception("Agent run failed, run_id=%s", run_id)
            await self._store.fail(
                run_id,
                Problem(
                    title="Agent runtime failed",
                    status=500,
                    code="agent_runtime_failure",
                ),
            )
        finally:
            if agent_type is not None:
                duration_seconds = time.perf_counter() - started_at
                self._metrics.finished(agent_type, terminal_status, duration_seconds)
                logger.info(
                    "Agent run finished run_id=%s request_id=%s agent_type=%s status=%s duration_ms=%d",
                    run_id,
                    request_id,
                    agent_type,
                    terminal_status,
                    round(duration_seconds * 1000),
                )
            async with self._tasks_lock:
                self._tasks.pop(run_id, None)

    async def _resume(self, record: RunRecord, decision: ApprovalDecision) -> None:
        terminal_status = "failed"
        started_at = time.perf_counter()
        agent_type = str(record.request.agent_type)
        try:
            self._metrics.started(agent_type)
            outcome = await self._runtime.resume(
                record.request,
                record.run_id,
                decision,
                record.delegated_authorization,
            )
            if outcome.failure is not None:
                await self._store.fail(record.run_id, outcome.failure)
                return
            if outcome.waiting_approval_id is not None:
                await self._store.wait_for_approval(record.run_id, outcome.waiting_approval_id)
                terminal_status = "waiting_approval"
                return
            if outcome.answer is None:
                raise RuntimeError("Agent runtime returned neither an answer nor a failure")
            sources = list(outcome.sources)
            if sources and not await self._store.publish(record.run_id, AgentEventType.SOURCES, {"sources": sources}):
                terminal_status = "cancelled"
                return
            if not await self._store.publish(record.run_id, AgentEventType.ANSWER_DELTA, {"delta": outcome.answer}):
                terminal_status = "cancelled"
                return
            await self._store.complete(record.run_id, {"sources": sources})
            terminal_status = "completed"
        except asyncio.CancelledError:
            terminal_status = "cancelled"
            raise
        except Exception:
            logger.exception("Agent workflow resume failed, run_id=%s", record.run_id)
            await self._store.fail(
                record.run_id,
                Problem(title="Agent runtime failed", status=500, code="agent_runtime_failure"),
            )
        finally:
            self._metrics.finished(agent_type, terminal_status, time.perf_counter() - started_at)
            async with self._tasks_lock:
                self._tasks.pop(record.run_id, None)

    @staticmethod
    def _approval_decision_fingerprint(decision: ApprovalDecision) -> str:
        payload = json.dumps(
            decision.model_dump(by_alias=True, mode="json", exclude_none=True),
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        )
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()
