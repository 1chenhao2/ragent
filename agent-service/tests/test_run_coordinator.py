from __future__ import annotations

import asyncio

from ragent_agent.contracts.models import AgentEventType, AgentRunRequest, ApprovalDecision, RunStatus
from ragent_agent.runtime.coordinator import AgentRunCoordinator
from ragent_agent.runtime.graph import RuntimeOutcome
from ragent_agent.runtime.store import RunStore
from ragent_agent.security.delegated_token import DelegatedPrincipal


def test_successful_agent_run_emits_sources_answer_and_one_terminal_event() -> None:
    class SuccessfulRuntime:
        async def execute(self, request, run_id, delegated_authorization):
            assert request.agent_type == "knowledge"
            assert run_id
            assert delegated_authorization == "Bearer delegated-token"
            return RuntimeOutcome(
                answer="Escalate to the incident commander. [1]",
                sources=(
                    {
                        "index": 1,
                        "docId": "document-1",
                        "docName": "Incident handbook",
                        "excerpt": "Escalate to the incident commander.",
                    },
                ),
            )

    request = AgentRunRequest.model_validate(
        {
            "requestId": "request-1",
            "conversationId": "conversation-1",
            "agentType": "knowledge",
            "input": "How should I escalate an incident?",
            "access": {
                "tenantId": None,
                "userId": "user-1",
                "roles": ["user"],
                "allowedKnowledgeBaseIds": ["knowledge-1"],
                "toolScopes": ["knowledge:read"],
            },
        }
    )
    principal = DelegatedPrincipal(
        user_id="user-1",
        tenant_id=None,
        roles=frozenset({"user"}),
        allowed_knowledge_base_ids=frozenset({"knowledge-1"}),
        tool_scopes=frozenset({"knowledge:read"}),
        authorization="Bearer delegated-token",
    )

    async def execute():
        coordinator = AgentRunCoordinator(RunStore(), SuccessfulRuntime(), auto_start_runs=True)
        record, created = await coordinator.create(request, principal, "idempotency-key-0001")
        queue, events, terminal = await coordinator.subscribe(record.run_id, principal, -1)
        try:
            while not terminal:
                event = await asyncio.wait_for(queue.get(), timeout=1)
                events.append(event)
                terminal = event.type in {
                    AgentEventType.RUN_COMPLETED,
                    AgentEventType.RUN_FAILED,
                    AgentEventType.RUN_CANCELLED,
                }
        finally:
            await coordinator.unsubscribe(record.run_id, queue)
        return created, await coordinator.state(record.run_id, principal), events

    created, state, events = asyncio.run(execute())

    assert created is True
    assert state.status == RunStatus.COMPLETED
    assert [event.type for event in events] == [
        AgentEventType.RUN_STARTED,
        AgentEventType.SOURCES,
        AgentEventType.ANSWER_DELTA,
        AgentEventType.RUN_COMPLETED,
    ]
    assert events[1].data["sources"][0]["docId"] == "document-1"
    assert events[2].data == {"delta": "Escalate to the incident commander. [1]"}
    assert events[3].data["sources"] == events[1].data["sources"]


def test_cancelled_run_ignores_a_late_runtime_outcome() -> None:
    class CancellationResistantRuntime:
        async def execute(self, request, run_id, delegated_authorization):
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                return RuntimeOutcome(answer="This late answer must be discarded.")

    request = AgentRunRequest.model_validate(
        {
            "requestId": "request-2",
            "conversationId": "conversation-1",
            "agentType": "knowledge",
            "input": "Start a cancellable run.",
            "access": {
                "tenantId": None,
                "userId": "user-1",
                "roles": ["user"],
                "allowedKnowledgeBaseIds": ["knowledge-1"],
                "toolScopes": ["knowledge:read"],
            },
        }
    )
    principal = DelegatedPrincipal(
        user_id="user-1",
        tenant_id=None,
        roles=frozenset({"user"}),
        allowed_knowledge_base_ids=frozenset({"knowledge-1"}),
        tool_scopes=frozenset({"knowledge:read"}),
        authorization="Bearer delegated-token",
    )

    async def execute():
        coordinator = AgentRunCoordinator(RunStore(), CancellationResistantRuntime(), auto_start_runs=True)
        record, _ = await coordinator.create(request, principal, "idempotency-key-0002")
        await asyncio.sleep(0)
        state = await coordinator.cancel(record.run_id, principal)
        await asyncio.sleep(0)
        queue, events, terminal = await coordinator.subscribe(record.run_id, principal, -1)
        await coordinator.unsubscribe(record.run_id, queue)
        return state, events, terminal

    state, events, terminal = asyncio.run(execute())

    assert state.status == RunStatus.CANCELLED
    assert terminal is True
    assert [event.type for event in events] == [AgentEventType.RUN_STARTED, AgentEventType.RUN_CANCELLED]


def test_run_store_reconciles_inflight_runs_after_restart() -> None:
    request = AgentRunRequest.model_validate(
        {
            "requestId": "request-restart-1",
            "conversationId": "conversation-1",
            "agentType": "knowledge",
            "input": "Recover this run after restart.",
            "access": {
                "tenantId": "tenant-1",
                "userId": "user-1",
                "roles": ["user"],
                "allowedKnowledgeBaseIds": ["knowledge-1"],
                "toolScopes": ["knowledge:read"],
            },
        }
    )
    principal = DelegatedPrincipal(
        user_id="user-1",
        tenant_id="tenant-1",
        roles=frozenset({"user"}),
        allowed_knowledge_base_ids=frozenset({"knowledge-1"}),
        tool_scopes=frozenset({"knowledge:read"}),
        authorization="Bearer delegated-token",
    )

    async def reconcile():
        store = RunStore()
        record, _ = await store.create_or_get(
            run_id="run-restart-1",
            request=request,
            principal=principal,
            idempotency_key="idempotency-restart-1",
        )
        await store.reconcile_after_restart()
        return await store.state_for(record.run_id, principal), await store.record_for(record.run_id, principal)

    state, record = asyncio.run(reconcile())

    assert state.status == RunStatus.FAILED
    assert state.error is not None
    assert state.error.code == "agent_service_restarted"
    assert [event.type for event in record.events] == [AgentEventType.RUN_STARTED, AgentEventType.RUN_FAILED]


def test_workflow_run_waits_for_approval_then_resumes_with_a_fresh_token() -> None:
    class WorkflowRuntime:
        async def execute(self, request, run_id, delegated_authorization):
            assert delegated_authorization == "Bearer original-token"
            return RuntimeOutcome(waiting_approval_id=f"{run_id}-approval")

        async def resume(self, request, run_id, decision, delegated_authorization):
            assert decision.approval_id == f"{run_id}-approval"
            assert delegated_authorization == "Bearer fresh-token"
            return RuntimeOutcome(answer="Workflow approval accepted.")

    request = AgentRunRequest.model_validate(
        {
            "requestId": "request-workflow-coordinator",
            "conversationId": "conversation-1",
            "agentType": "workflow",
            "input": "Publish the approved report.",
            "access": {
                "tenantId": None,
                "userId": "admin-1",
                "roles": ["admin"],
                "allowedKnowledgeBaseIds": [],
                "toolScopes": [],
            },
        }
    )
    principal = DelegatedPrincipal(
        user_id="admin-1",
        tenant_id=None,
        roles=frozenset({"admin"}),
        allowed_knowledge_base_ids=frozenset(),
        tool_scopes=frozenset(),
        authorization="Bearer original-token",
    )

    async def execute():
        store = RunStore()
        coordinator = AgentRunCoordinator(store, WorkflowRuntime(), auto_start_runs=True)
        record, _ = await coordinator.create(request, principal, "workflow-idempotency-1")
        queue, events, terminal = await coordinator.subscribe(record.run_id, principal, -1)
        while not terminal:
            event = await asyncio.wait_for(queue.get(), timeout=1)
            events.append(event)
            if event.type == AgentEventType.APPROVAL_REQUIRED:
                break
            terminal = event.type in {
                AgentEventType.RUN_COMPLETED,
                AgentEventType.RUN_FAILED,
                AgentEventType.RUN_CANCELLED,
            }
        waiting = await coordinator.state(record.run_id, principal)
        resumed = await coordinator.resume(
            record.run_id,
            principal,
            ApprovalDecision.model_validate(
                {"approvalId": f"{record.run_id}-approval", "decision": "approve"}
            ),
            "Bearer fresh-token",
            "resume-idempotency-key-0001",
        )
        repeated = await coordinator.resume(
            record.run_id,
            principal,
            ApprovalDecision.model_validate(
                {"approvalId": f"{record.run_id}-approval", "decision": "approve"}
            ),
            "Bearer fresh-token",
            "resume-idempotency-key-0001",
        )
        while True:
            event = await asyncio.wait_for(queue.get(), timeout=1)
            events.append(event)
            resumed = await coordinator.state(record.run_id, principal)
            if event.type in {
                AgentEventType.RUN_COMPLETED,
                AgentEventType.RUN_FAILED,
                AgentEventType.RUN_CANCELLED,
            }:
                break
        await coordinator.unsubscribe(record.run_id, queue)
        return waiting, resumed, repeated, events

    waiting, completed, repeated, events = asyncio.run(execute())

    assert waiting.status == RunStatus.WAITING_APPROVAL
    assert waiting.waiting_approval_id.endswith("-approval")
    assert completed.status == RunStatus.COMPLETED
    assert repeated.status == RunStatus.RUNNING
    assert [event.type for event in events] == [
        AgentEventType.RUN_STARTED,
        AgentEventType.APPROVAL_REQUIRED,
        AgentEventType.ANSWER_DELTA,
        AgentEventType.RUN_COMPLETED,
    ]
