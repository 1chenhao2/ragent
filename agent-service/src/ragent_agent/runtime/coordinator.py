from __future__ import annotations

import asyncio
import logging
import uuid

from ragent_agent.contracts.models import AgentRunRequest, AgentRunState, Problem
from ragent_agent.runtime.graph import LangGraphAgentRuntime
from ragent_agent.runtime.store import RunRecord, RunStore
from ragent_agent.security.delegated_token import DelegatedPrincipal

logger = logging.getLogger(__name__)


class AgentRunCoordinator:
    def __init__(self, store: RunStore, runtime: LangGraphAgentRuntime, auto_start_runs: bool) -> None:
        self._store = store
        self._runtime = runtime
        self._auto_start_runs = auto_start_runs
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

    async def resume(self, run_id: str, principal: DelegatedPrincipal) -> AgentRunState:
        return await self._store.require_waiting_approval(run_id, principal)

    async def subscribe(self, run_id: str, principal: DelegatedPrincipal, after_sequence: int):
        return await self._store.subscribe(run_id, principal, after_sequence)

    async def unsubscribe(self, run_id: str, queue) -> None:
        await self._store.unsubscribe(run_id, queue)

    async def _execute(self, run_id: str) -> None:
        try:
            record = await self._store.record_internal(run_id)
            outcome = await self._runtime.execute(record.request, run_id)
            if outcome.failure is not None:
                await self._store.fail(run_id, outcome.failure)
        except asyncio.CancelledError:
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
            async with self._tasks_lock:
                self._tasks.pop(run_id, None)
