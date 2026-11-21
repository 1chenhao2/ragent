from __future__ import annotations

import asyncio
import json

import httpx

from ragent_agent.config import AgentServiceSettings
from ragent_agent.contracts.models import AgentRunRequest, ApprovalDecision
from ragent_agent.rag.models import RagContext, SourceReference
from ragent_agent.runtime.graph import LangGraphAgentRuntime

TEST_SECRET = "test-delegated-token-secret-0123456789"


def test_langgraph_registers_business_tools_but_remains_fail_closed() -> None:
    settings = AgentServiceSettings(
        delegated_token_hmac_secret=TEST_SECRET,
        delegated_token_issuer="ragent-java",
        delegated_token_audience="ragent-agent-service",
        business_tool_base_url="http://java-business-system",
    )
    request = AgentRunRequest.model_validate(
        {
            "requestId": "request-1",
            "conversationId": "conversation-1",
            "agentType": "knowledge",
            "input": "List the available knowledge bases.",
            "access": {
                "tenantId": None,
                "userId": "user-1",
                "roles": ["user"],
                "allowedKnowledgeBaseIds": ["knowledge-1"],
                "toolScopes": ["knowledge:read"],
            },
        }
    )

    async def execute() -> tuple[tuple[str, ...], str | None]:
        runtime = LangGraphAgentRuntime(settings)
        try:
            outcome = await runtime.execute(request, "agent-run-1", "Bearer delegated-token")
            return runtime.registered_tool_names, outcome.failure.code if outcome.failure else None
        finally:
            await runtime.aclose()

    tool_names, failure_code = asyncio.run(execute())

    assert tool_names == ("knowledge_base_list", "dashboard_overview")
    assert failure_code == "agent_runtime_not_configured"


def test_langgraph_answers_only_from_authorized_knowledge_bases() -> None:
    requests: list[httpx.Request] = []

    def handle(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        command = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "toolCallId": command["toolCallId"],
                "toolName": "knowledge_base.list",
                "output": {
                    "knowledgeBases": [
                        {
                            "id": "knowledge-1",
                            "name": "Operations",
                            "collectionName": "operations",
                            "embeddingModel": "qwen-emb-8b",
                        }
                    ]
                },
            },
            request=request,
        )

    class FakeRetriever:
        async def retrieve(self, request):
            assert request.knowledge_bases[0].collection_name == "operations"
            assert request.tenant_id is None
            return RagContext(
                prompt_context="[1] The incident handbook requires an owner.",
                sources=(
                    SourceReference(
                        index=1,
                        doc_id="document-1",
                        excerpt="The incident handbook requires an owner.",
                    ),
                ),
            )

    class FakeAnswerGenerator:
        async def generate(self, question: str, context: str, locale: str) -> str:
            assert question == "Who owns the incident handbook?"
            assert context == "[1] The incident handbook requires an owner."
            assert locale == "zh-CN"
            return "The incident handbook requires an owner. [1]"

    settings = AgentServiceSettings(
        delegated_token_hmac_secret=TEST_SECRET,
        delegated_token_issuer="ragent-java",
        delegated_token_audience="ragent-agent-service",
        business_tool_base_url="http://java-business-system",
        rag_enabled=True,
    )
    request = AgentRunRequest.model_validate(
        {
            "requestId": "request-1",
            "conversationId": "conversation-1",
            "agentType": "knowledge",
            "input": "Who owns the incident handbook?",
            "access": {
                "tenantId": None,
                "userId": "user-1",
                "roles": ["user"],
                "allowedKnowledgeBaseIds": ["knowledge-1"],
                "toolScopes": ["knowledge:read"],
            },
        }
    )

    async def execute():
        runtime = LangGraphAgentRuntime(
            settings,
            http_transport=httpx.MockTransport(handle),
            rag_retriever=FakeRetriever(),
            answer_generator=FakeAnswerGenerator(),
        )
        try:
            return await runtime.execute(request, "agent-run-1", "Bearer delegated-token")
        finally:
            await runtime.aclose()

    outcome = asyncio.run(execute())

    assert outcome.failure is None
    assert outcome.answer == "The incident handbook requires an owner. [1]"
    assert outcome.sources == (
        {
            "index": 1,
            "docId": "document-1",
            "excerpt": "The incident handbook requires an owner.",
        },
    )
    assert len(requests) == 1
    assert json.loads(requests[0].content)["arguments"] == {"knowledgeBaseIds": ["knowledge-1"]}


def test_enabled_rag_with_incomplete_provider_configuration_fails_closed() -> None:
    settings = AgentServiceSettings(
        delegated_token_hmac_secret=TEST_SECRET,
        delegated_token_issuer="ragent-java",
        delegated_token_audience="ragent-agent-service",
        rag_enabled=True,
    )
    request = AgentRunRequest.model_validate(
        {
            "requestId": "request-1",
            "conversationId": "conversation-1",
            "agentType": "knowledge",
            "input": "Summarize the authorized knowledge.",
            "access": {
                "tenantId": None,
                "userId": "user-1",
                "roles": ["user"],
                "allowedKnowledgeBaseIds": ["knowledge-1"],
                "toolScopes": ["knowledge:read"],
            },
        }
    )

    async def execute():
        runtime = LangGraphAgentRuntime(settings)
        try:
            return await runtime.execute(request, "agent-run-1", "Bearer delegated-token")
        finally:
            await runtime.aclose()

    outcome = asyncio.run(execute())

    assert outcome.answer is None
    assert outcome.failure is not None
    assert outcome.failure.code == "rag_runtime_misconfigured"


def test_langgraph_preserves_stable_business_tool_failure_code() -> None:
    def handle(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            403,
            json={
                "title": "Forbidden",
                "status": 403,
                "code": "tool_scope_forbidden",
                "detail": "Missing required Agent Tool scope",
            },
            request=request,
        )

    class UnusedRetriever:
        async def retrieve(self, request):
            raise AssertionError("RAG retrieval must not run after a Tool rejection")

    class UnusedAnswerGenerator:
        async def generate(self, question: str, context: str, locale: str) -> str:
            raise AssertionError("Answer generation must not run after a Tool rejection")

    settings = AgentServiceSettings(
        delegated_token_hmac_secret=TEST_SECRET,
        delegated_token_issuer="ragent-java",
        delegated_token_audience="ragent-agent-service",
        business_tool_base_url="http://java-business-system",
        rag_enabled=True,
    )
    request = AgentRunRequest.model_validate(
        {
            "requestId": "request-tool-failure",
            "conversationId": "conversation-1",
            "agentType": "knowledge",
            "input": "List the authorized knowledge bases.",
            "access": {
                "tenantId": None,
                "userId": "user-1",
                "roles": ["user"],
                "allowedKnowledgeBaseIds": ["knowledge-1"],
                "toolScopes": ["knowledge:read"],
            },
        }
    )

    async def execute():
        runtime = LangGraphAgentRuntime(
            settings,
            http_transport=httpx.MockTransport(handle),
            rag_retriever=UnusedRetriever(),
            answer_generator=UnusedAnswerGenerator(),
        )
        try:
            return await runtime.execute(request, "agent-run-tool-failure", "Bearer delegated-token")
        finally:
            await runtime.aclose()

    outcome = asyncio.run(execute())

    assert outcome.answer is None
    assert outcome.failure is not None
    assert outcome.failure.status == 403
    assert outcome.failure.code == "tool_scope_forbidden"


def test_langgraph_maps_llm_provider_failure_without_exposing_provider_detail() -> None:
    def handle(request: httpx.Request) -> httpx.Response:
        command = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "toolCallId": command["toolCallId"],
                "toolName": "knowledge_base.list",
                "output": {
                    "knowledgeBases": [
                        {
                            "id": "knowledge-1",
                            "name": "Operations",
                            "collectionName": "operations",
                            "embeddingModel": "qwen-emb-8b",
                        }
                    ]
                },
            },
            request=request,
        )

    class FakeRetriever:
        async def retrieve(self, request):
            return RagContext(prompt_context="[1] Authorized evidence.", sources=())

    class FailingAnswerGenerator:
        async def generate(self, question: str, context: str, locale: str) -> str:
            raise RuntimeError("provider-internal-response-must-not-leak")

    settings = AgentServiceSettings(
        delegated_token_hmac_secret=TEST_SECRET,
        delegated_token_issuer="ragent-java",
        delegated_token_audience="ragent-agent-service",
        business_tool_base_url="http://java-business-system",
        rag_enabled=True,
    )
    request = AgentRunRequest.model_validate(
        {
            "requestId": "request-provider-failure",
            "conversationId": "conversation-1",
            "agentType": "knowledge",
            "input": "Summarize the authorized evidence.",
            "access": {
                "tenantId": None,
                "userId": "user-1",
                "roles": ["user"],
                "allowedKnowledgeBaseIds": ["knowledge-1"],
                "toolScopes": ["knowledge:read"],
            },
        }
    )

    async def execute():
        runtime = LangGraphAgentRuntime(
            settings,
            http_transport=httpx.MockTransport(handle),
            rag_retriever=FakeRetriever(),
            answer_generator=FailingAnswerGenerator(),
        )
        try:
            return await runtime.execute(request, "agent-run-provider-failure", "Bearer delegated-token")
        finally:
            await runtime.aclose()

    outcome = asyncio.run(execute())

    assert outcome.answer is None
    assert outcome.failure is not None
    assert outcome.failure.status == 502
    assert outcome.failure.code == "llm_provider_failed"
    assert outcome.failure.detail == "The configured language model could not complete the Agent response."


def test_langgraph_maps_retrieval_failure_without_exposing_dependency_detail() -> None:
    def handle(request: httpx.Request) -> httpx.Response:
        command = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "toolCallId": command["toolCallId"],
                "toolName": "knowledge_base.list",
                "output": {
                    "knowledgeBases": [
                        {
                            "id": "knowledge-1",
                            "name": "Operations",
                            "collectionName": "operations",
                            "embeddingModel": "qwen-emb-8b",
                        }
                    ]
                },
            },
            request=request,
        )

    class FailingRetriever:
        async def retrieve(self, request):
            raise RuntimeError("database-host-and-provider-detail-must-not-leak")

    class UnusedAnswerGenerator:
        async def generate(self, question: str, context: str, locale: str) -> str:
            raise AssertionError("Answer generation must not run after retrieval fails")

    settings = AgentServiceSettings(
        delegated_token_hmac_secret=TEST_SECRET,
        delegated_token_issuer="ragent-java",
        delegated_token_audience="ragent-agent-service",
        business_tool_base_url="http://java-business-system",
        rag_enabled=True,
    )
    request = AgentRunRequest.model_validate(
        {
            "requestId": "request-retrieval-failure",
            "conversationId": "conversation-1",
            "agentType": "knowledge",
            "input": "Search the authorized evidence.",
            "access": {
                "tenantId": None,
                "userId": "user-1",
                "roles": ["user"],
                "allowedKnowledgeBaseIds": ["knowledge-1"],
                "toolScopes": ["knowledge:read"],
            },
        }
    )

    async def execute():
        runtime = LangGraphAgentRuntime(
            settings,
            http_transport=httpx.MockTransport(handle),
            rag_retriever=FailingRetriever(),
            answer_generator=UnusedAnswerGenerator(),
        )
        try:
            return await runtime.execute(request, "agent-run-retrieval-failure", "Bearer delegated-token")
        finally:
            await runtime.aclose()

    outcome = asyncio.run(execute())

    assert outcome.answer is None
    assert outcome.failure is not None
    assert outcome.failure.status == 503
    assert outcome.failure.code == "rag_retrieval_failed"
    assert outcome.failure.detail == "Authorized knowledge retrieval is temporarily unavailable."


def test_data_analysis_agent_generates_an_authorized_dashboard_summary_without_rag_retrieval() -> None:
    requests: list[httpx.Request] = []

    def handle(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        command = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "toolCallId": command["toolCallId"],
                "toolName": "dashboard.overview",
                "output": {
                    "window": "7d",
                    "updatedAt": 1710000000000,
                    "kpis": {"messages24h": {"value": 42}},
                },
            },
            request=request,
        )

    class UnusedRetriever:
        async def retrieve(self, request):
            raise AssertionError("Data analysis must not query pgvector")

    class FakeAnswerGenerator:
        async def generate(self, question: str, context: str, locale: str) -> str:
            raise AssertionError("Knowledge answer generation must not run for dashboard analysis")

        async def generate_data_analysis(self, question: str, context: str, locale: str) -> str:
            assert question == "Summarize the dashboard performance."
            assert context == json.dumps(
                {
                    "kpis": {"messages24h": {"value": 42}},
                    "updatedAt": 1710000000000,
                    "window": "7d",
                },
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
            )
            assert locale == "zh-CN"
            return "Dashboard activity is stable, with 42 messages in the reported period."

    settings = AgentServiceSettings(
        delegated_token_hmac_secret=TEST_SECRET,
        delegated_token_issuer="ragent-java",
        delegated_token_audience="ragent-agent-service",
        business_tool_base_url="http://java-business-system",
        rag_enabled=True,
    )
    request = AgentRunRequest.model_validate(
        {
            "requestId": "request-data-analysis-1",
            "conversationId": "conversation-1",
            "agentType": "data_analysis",
            "input": "Summarize the dashboard performance.",
            "access": {
                "tenantId": None,
                "userId": "admin-1",
                "roles": ["admin"],
                "allowedKnowledgeBaseIds": [],
                "toolScopes": ["dashboard:read"],
            },
        }
    )

    async def execute():
        runtime = LangGraphAgentRuntime(
            settings,
            http_transport=httpx.MockTransport(handle),
            rag_retriever=UnusedRetriever(),
            answer_generator=FakeAnswerGenerator(),
        )
        try:
            return await runtime.execute(request, "agent-run-dashboard-1", "Bearer delegated-token")
        finally:
            await runtime.aclose()

    outcome = asyncio.run(execute())

    assert outcome.failure is None
    assert outcome.answer == "Dashboard activity is stable, with 42 messages in the reported period."
    assert outcome.sources == ()
    assert len(requests) == 1
    command = json.loads(requests[0].content)
    assert command["toolName"] == "dashboard.overview"
    assert command["arguments"] == {"window": "7d"}


def test_workflow_runtime_pauses_and_resumes_through_langgraph_checkpoint() -> None:
    settings = AgentServiceSettings(
        delegated_token_hmac_secret=TEST_SECRET,
        delegated_token_issuer="ragent-java",
        delegated_token_audience="ragent-agent-service",
        rag_enabled=True,
    )
    request = AgentRunRequest.model_validate(
        {
            "requestId": "request-workflow-1",
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

    async def execute():
        runtime = LangGraphAgentRuntime(settings)
        try:
            waiting = await runtime.execute(request, "workflow-run-1", "Bearer old-token")
            resumed = await runtime.resume(
                request,
                "workflow-run-1",
                ApprovalDecision.model_validate(
                    {"approvalId": "workflow-run-1-approval", "decision": "approve"}
                ),
                "Bearer fresh-token",
            )
            return waiting, resumed
        finally:
            await runtime.aclose()

    waiting, resumed = asyncio.run(execute())

    assert waiting.failure is None
    assert waiting.answer is None
    assert waiting.waiting_approval_id == "workflow-run-1-approval"
    assert resumed.failure is None
    assert resumed.answer == "Workflow approval accepted."
