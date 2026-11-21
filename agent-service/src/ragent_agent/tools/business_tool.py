from __future__ import annotations

import hashlib
import json
from typing import Annotated, Any

import httpx
from langchain_core.tools import BaseTool, InjectedToolArg, tool
from langgraph.prebuilt import InjectedState

from ragent_agent.contracts.models import BusinessToolCommand, BusinessToolResult, Problem


class BusinessToolError(RuntimeError):
    def __init__(self, status: int, code: str, detail: str) -> None:
        self.status = status
        self.code = code
        self.detail = detail
        super().__init__(detail)


class BusinessToolClient:
    def __init__(self, http_client: httpx.AsyncClient) -> None:
        self._http_client = http_client

    async def invoke(self, authorization: str, command: BusinessToolCommand) -> BusinessToolResult:
        try:
            response = await self._http_client.post(
                "/internal/v1/agent-tools/invoke",
                headers={"Authorization": authorization},
                json=command.model_dump(by_alias=True, mode="json"),
            )
        except httpx.TimeoutException as error:
            raise BusinessToolError(504, "business_tool_timeout", "Java business Tool invocation timed out") from error
        except httpx.HTTPError as error:
            raise BusinessToolError(
                503,
                "business_tool_unavailable",
                "Java business Tool service is unavailable",
            ) from error
        if response.is_error:
            raise self._error(response)

        result = BusinessToolResult.model_validate(response.json())
        if result.tool_call_id != command.tool_call_id or result.tool_name != command.tool_name:
            raise BusinessToolError(502, "invalid_business_tool_response", "Business Tool response identity mismatch")
        return result

    @staticmethod
    def _error(response: httpx.Response) -> BusinessToolError:
        try:
            problem = Problem.model_validate(response.json())
        except (ValueError, TypeError):
            return BusinessToolError(
                response.status_code,
                "business_tool_error",
                "Java business system rejected the Tool invocation",
            )
        return BusinessToolError(
            response.status_code,
            problem.code or "business_tool_error",
            problem.detail or problem.title,
        )


def create_knowledge_base_list_tool(client: BusinessToolClient) -> BaseTool:
    @tool("knowledge_base_list")
    async def knowledge_base_list(
        knowledge_base_ids: list[str],
        run_id: Annotated[str, InjectedToolArg(), InjectedState("run_id")],
        delegated_authorization: Annotated[str, InjectedToolArg(), InjectedState("delegated_authorization")],
    ) -> dict[str, Any]:
        """List Java-owned knowledge bases that are inside the delegated resource scope."""
        arguments = {"knowledgeBaseIds": knowledge_base_ids}
        invocation_id = _invocation_id(run_id, "knowledge_base.list", arguments)
        command = BusinessToolCommand(
            toolName="knowledge_base.list",
            toolCallId=invocation_id,
            idempotencyKey=invocation_id,
            arguments=arguments,
        )
        result = await client.invoke(delegated_authorization, command)
        return result.output

    return knowledge_base_list


def create_knowledge_document_overview_tool(client: BusinessToolClient) -> BaseTool:
    @tool("knowledge_document_overview")
    async def knowledge_document_overview(
        knowledge_base_ids: list[str] | None = None,
        run_id: Annotated[str, InjectedToolArg(), InjectedState("run_id")] = "",
        delegated_authorization: Annotated[str, InjectedToolArg(), InjectedState("delegated_authorization")] = "",
    ) -> dict[str, Any]:
        """Read a bounded ingestion overview of knowledge documents in the delegated scope."""
        arguments: dict[str, Any] = {}
        if knowledge_base_ids:
            arguments["knowledgeBaseIds"] = list(knowledge_base_ids)
        invocation_id = _invocation_id(run_id, "knowledge_document.overview", arguments)
        command = BusinessToolCommand(
            toolName="knowledge_document.overview",
            toolCallId=invocation_id,
            idempotencyKey=invocation_id,
            arguments=arguments,
        )
        result = await client.invoke(delegated_authorization, command)
        return result.output

    return knowledge_document_overview


def create_dashboard_overview_tool(client: BusinessToolClient) -> BaseTool:
    @tool("dashboard_overview")
    async def dashboard_overview(
        window: str = "7d",
        run_id: Annotated[str, InjectedToolArg(), InjectedState("run_id")] = "",
        delegated_authorization: Annotated[str, InjectedToolArg(), InjectedState("delegated_authorization")] = "",
    ) -> dict[str, Any]:
        """Read an admin-authorized dashboard overview for a bounded reporting window."""
        arguments = {"window": window}
        invocation_id = _invocation_id(run_id, "dashboard.overview", arguments)
        command = BusinessToolCommand(
            toolName="dashboard.overview",
            toolCallId=invocation_id,
            idempotencyKey=invocation_id,
            arguments=arguments,
        )
        result = await client.invoke(delegated_authorization, command)
        return result.output

    return dashboard_overview


def _invocation_id(run_id: str, tool_name: str, arguments: dict[str, Any]) -> str:
    payload = json.dumps(arguments, ensure_ascii=True, separators=(",", ":"), sort_keys=True)
    digest = hashlib.sha256(f"{run_id}:{tool_name}:{payload}".encode()).hexdigest()[:32]
    return f"{run_id}-{digest}"
