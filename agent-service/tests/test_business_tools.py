from __future__ import annotations

import asyncio
import json

import httpx
import pytest

from ragent_agent.contracts.models import BusinessToolCommand
from ragent_agent.tools.business_tool import (
    BusinessToolClient,
    BusinessToolError,
    create_dashboard_overview_tool,
    create_knowledge_base_list_tool,
    create_knowledge_document_overview_tool,
)


def test_langchain_knowledge_base_tool_calls_java_with_delegated_identity() -> None:
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

    async def invoke() -> dict[str, object]:
        async with httpx.AsyncClient(
            base_url="http://java-business-system",
            transport=httpx.MockTransport(handle),
        ) as http_client:
            tool_client = BusinessToolClient(http_client)
            tool = create_knowledge_base_list_tool(tool_client)
            return await tool.ainvoke(
                {
                    "knowledge_base_ids": ["knowledge-1"],
                    "run_id": "agent-run-1",
                    "delegated_authorization": "Bearer delegated-token",
                }
            )

    output = asyncio.run(invoke())

    assert output["knowledgeBases"] == [
        {
            "id": "knowledge-1",
            "name": "Operations",
            "collectionName": "operations",
            "embeddingModel": "qwen-emb-8b",
        }
    ]
    assert len(requests) == 1
    request = requests[0]
    assert request.url.path == "/internal/v1/agent-tools/invoke"
    assert request.headers["Authorization"] == "Bearer delegated-token"
    command = json.loads(request.content)
    assert command["toolName"] == "knowledge_base.list"
    assert command["toolCallId"].startswith("agent-run-1-")
    assert command["idempotencyKey"].startswith("agent-run-1-")
    assert command["arguments"] == {"knowledgeBaseIds": ["knowledge-1"]}


def test_business_tool_client_maps_java_timeout_to_stable_error() -> None:
    def handle(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("java-host-detail-must-not-leak", request=request)

    async def invoke() -> None:
        async with httpx.AsyncClient(
            base_url="http://java-business-system",
            transport=httpx.MockTransport(handle),
        ) as http_client:
            await BusinessToolClient(http_client).invoke(
                "Bearer delegated-token",
                BusinessToolCommand(
                    toolName="knowledge_base.list",
                    toolCallId="tool-call-00000001",
                    idempotencyKey="idempotency-key-0001",
                    arguments={"knowledgeBaseIds": ["knowledge-1"]},
                ),
            )

    with pytest.raises(BusinessToolError) as captured:
        asyncio.run(invoke())

    assert captured.value.status == 504
    assert captured.value.code == "business_tool_timeout"
    assert captured.value.detail == "Java business Tool invocation timed out"


def test_business_tool_client_maps_java_connection_failure_to_stable_error() -> None:
    def handle(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("java-host-detail-must-not-leak", request=request)

    async def invoke() -> None:
        async with httpx.AsyncClient(
            base_url="http://java-business-system",
            transport=httpx.MockTransport(handle),
        ) as http_client:
            await BusinessToolClient(http_client).invoke(
                "Bearer delegated-token",
                BusinessToolCommand(
                    toolName="knowledge_base.list",
                    toolCallId="tool-call-00000001",
                    idempotencyKey="idempotency-key-0001",
                    arguments={"knowledgeBaseIds": ["knowledge-1"]},
                ),
            )

    with pytest.raises(BusinessToolError) as captured:
        asyncio.run(invoke())

    assert captured.value.status == 503
    assert captured.value.code == "business_tool_unavailable"
    assert captured.value.detail == "Java business Tool service is unavailable"


def test_langchain_dashboard_tool_requests_the_bounded_default_window() -> None:
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

    async def invoke() -> dict[str, object]:
        async with httpx.AsyncClient(
            base_url="http://java-business-system",
            transport=httpx.MockTransport(handle),
        ) as http_client:
            tool = create_dashboard_overview_tool(BusinessToolClient(http_client))
            return await tool.ainvoke(
                {
                    "run_id": "agent-run-dashboard-1",
                    "delegated_authorization": "Bearer delegated-token",
                }
            )

    output = asyncio.run(invoke())

    assert output["window"] == "7d"
    assert len(requests) == 1
    request = requests[0]
    assert request.headers["Authorization"] == "Bearer delegated-token"
    command = json.loads(request.content)
    assert command["toolName"] == "dashboard.overview"
    assert command["arguments"] == {"window": "7d"}


def test_langchain_knowledge_document_overview_tool_sends_requested_kb_ids() -> None:
    requests: list[httpx.Request] = []

    def handle(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        command = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "toolCallId": command["toolCallId"],
                "toolName": "knowledge_document.overview",
                "output": {
                    "knowledgeBases": [
                        {
                            "id": "knowledge-1",
                            "name": "Operations",
                            "totals": {"documents": 13, "chunks": 499, "bytes": 12_978_245},
                            "byStatus": {
                                "pending": 1,
                                "running": 1,
                                "failed": 1,
                                "success": 8,
                                "disabled": 2,
                            },
                        }
                    ]
                },
            },
            request=request,
        )

    async def invoke() -> dict[str, object]:
        async with httpx.AsyncClient(
            base_url="http://java-business-system",
            transport=httpx.MockTransport(handle),
        ) as http_client:
            tool = create_knowledge_document_overview_tool(BusinessToolClient(http_client))
            return await tool.ainvoke(
                {
                    "knowledge_base_ids": ["knowledge-1"],
                    "run_id": "agent-run-business-1",
                    "delegated_authorization": "Bearer delegated-token",
                }
            )

    output = asyncio.run(invoke())

    assert output["knowledgeBases"][0]["totals"]["documents"] == 13
    assert len(requests) == 1
    request = requests[0]
    assert request.headers["Authorization"] == "Bearer delegated-token"
    command = json.loads(request.content)
    assert command["toolName"] == "knowledge_document.overview"
    assert command["arguments"] == {"knowledgeBaseIds": ["knowledge-1"]}


def test_langchain_knowledge_document_overview_tool_omits_kb_ids_when_absent() -> None:
    requests: list[httpx.Request] = []

    def handle(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        command = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "toolCallId": command["toolCallId"],
                "toolName": "knowledge_document.overview",
                "output": {"knowledgeBases": []},
            },
            request=request,
        )

    async def invoke() -> dict[str, object]:
        async with httpx.AsyncClient(
            base_url="http://java-business-system",
            transport=httpx.MockTransport(handle),
        ) as http_client:
            tool = create_knowledge_document_overview_tool(BusinessToolClient(http_client))
            return await tool.ainvoke(
                {
                    "run_id": "agent-run-business-2",
                    "delegated_authorization": "Bearer delegated-token",
                }
            )

    output = asyncio.run(invoke())

    assert output == {"knowledgeBases": []}
    command = json.loads(requests[0].content)
    assert command["arguments"] == {}


def test_business_tool_client_maps_resource_scope_forbidden_to_stable_error() -> None:
    def handle(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            403,
            json={
                "type": "about:blank",
                "title": "Forbidden",
                "status": 403,
                "code": "resource_scope_forbidden",
                "detail": "Requested knowledge base is outside delegated scope",
            },
            request=request,
        )

    async def invoke() -> None:
        async with httpx.AsyncClient(
            base_url="http://java-business-system",
            transport=httpx.MockTransport(handle),
        ) as http_client:
            await BusinessToolClient(http_client).invoke(
                "Bearer delegated-token",
                BusinessToolCommand(
                    toolName="knowledge_document.overview",
                    toolCallId="tool-call-kdov-0001",
                    idempotencyKey="idempotency-kdov-0001",
                    arguments={"knowledgeBaseIds": ["knowledge-1"]},
                ),
            )

    with pytest.raises(BusinessToolError) as captured:
        asyncio.run(invoke())

    assert captured.value.status == 403
    assert captured.value.code == "resource_scope_forbidden"
    assert "outside delegated scope" in captured.value.detail
