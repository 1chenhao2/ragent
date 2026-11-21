from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from typing import Annotated, Any, TypedDict

import httpx
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, ToolMessage
from langgraph.channels import UntrackedValue
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages
from langgraph.prebuilt import ToolNode
from langgraph.types import Command, interrupt

from ragent_agent.config import AgentServiceSettings
from ragent_agent.contracts.models import AgentRunRequest, AgentType, ApprovalDecision, Problem
from ragent_agent.rag.answer import AnswerGenerator
from ragent_agent.rag.errors import (
    RagAuthorizationError,
    RagConfigurationError,
    RagGenerationError,
    RagRetrievalError,
)
from ragent_agent.rag.factory import create_answer_generator, create_retriever
from ragent_agent.rag.models import KnowledgeBase, RagRetrievalRequest
from ragent_agent.rag.retriever import RagRetriever
from ragent_agent.runtime.checkpoint import CheckpointStore, create_checkpoint_store
from ragent_agent.tools.business_tool import (
    BusinessToolClient,
    BusinessToolError,
    create_dashboard_overview_tool,
    create_knowledge_base_list_tool,
    create_knowledge_document_overview_tool,
)


class AgentGraphState(TypedDict, total=False):
    run_id: str
    agent_type: str
    input: str
    messages: Annotated[list[BaseMessage], add_messages]
    delegated_authorization: Annotated[str, UntrackedValue(str)]
    tenant_id: str | None
    locale: str
    knowledge_base_ids: tuple[str, ...]
    knowledge_bases: tuple[KnowledgeBase, ...]
    rag_context: str
    sources: tuple[dict[str, Any], ...]
    answer: str
    dashboard_context: str
    business_context: str
    workflow_decision: dict[str, Any]


@dataclass(frozen=True)
class RuntimeOutcome:
    failure: Problem | None = None
    answer: str | None = None
    sources: tuple[dict[str, Any], ...] = ()
    waiting_approval_id: str | None = None


class LangGraphAgentRuntime:
    def __init__(
        self,
        settings: AgentServiceSettings,
        *,
        http_transport: httpx.AsyncBaseTransport | None = None,
        rag_retriever: RagRetriever | None = None,
        answer_generator: AnswerGenerator | None = None,
        checkpoint_store: CheckpointStore | None = None,
    ) -> None:
        self._settings = settings
        self._http_client = httpx.AsyncClient(
            base_url=settings.business_tool_base_url,
            timeout=settings.business_tool_timeout_seconds,
            transport=http_transport,
        )
        tool_client = BusinessToolClient(self._http_client)
        self._business_tools = (
            create_knowledge_base_list_tool(tool_client),
            create_dashboard_overview_tool(tool_client),
            create_knowledge_document_overview_tool(tool_client),
        )
        self._rag_retriever = rag_retriever
        self._answer_generator = answer_generator
        self._rag_initialization_lock = asyncio.Lock()
        self._checkpoint_store = checkpoint_store or create_checkpoint_store(settings)

        graph = StateGraph(AgentGraphState)
        graph.add_node("prepare_run", self._prepare_run)
        graph.add_node("request_knowledge_bases", self._request_knowledge_bases)
        graph.add_node("request_dashboard_overview", self._request_dashboard_overview)
        graph.add_node("request_business_overview", self._request_business_overview)
        graph.add_node("business_tools", ToolNode(list(self._business_tools)))
        graph.add_node("retrieve_context", self._retrieve_context)
        graph.add_node("generate_answer", self._generate_answer)
        graph.add_node("generate_dashboard_analysis", self._generate_dashboard_analysis)
        graph.add_node("generate_business_analysis", self._generate_business_analysis)
        graph.add_node("workflow_approval", self._workflow_approval)
        graph.add_edge(START, "prepare_run")
        graph.add_conditional_edges(
            "prepare_run",
            self._agent_path,
            {
                AgentType.KNOWLEDGE.value: "request_knowledge_bases",
                AgentType.DATA_ANALYSIS.value: "request_dashboard_overview",
                AgentType.BUSINESS.value: "request_business_overview",
                AgentType.WORKFLOW.value: "workflow_approval",
            },
        )
        graph.add_edge("request_knowledge_bases", "business_tools")
        graph.add_edge("request_dashboard_overview", "business_tools")
        graph.add_edge("request_business_overview", "business_tools")
        graph.add_conditional_edges(
            "business_tools",
            self._after_tool_path,
            {
                AgentType.KNOWLEDGE.value: "retrieve_context",
                AgentType.DATA_ANALYSIS.value: "generate_dashboard_analysis",
                AgentType.BUSINESS.value: "generate_business_analysis",
            },
        )
        graph.add_edge("retrieve_context", "generate_answer")
        graph.add_edge("generate_answer", END)
        graph.add_edge("generate_dashboard_analysis", END)
        graph.add_edge("generate_business_analysis", END)
        self._graph_definition = graph
        self._graph = None
        self._compile_graph_if_available()

    @property
    def registered_tool_names(self) -> tuple[str, ...]:
        return tuple(tool.name for tool in self._business_tools)

    async def execute(
        self,
        request: AgentRunRequest,
        run_id: str,
        delegated_authorization: str,
    ) -> RuntimeOutcome:
        enabled_agent_types = {
            AgentType.KNOWLEDGE,
            AgentType.DATA_ANALYSIS,
            AgentType.BUSINESS,
            AgentType.WORKFLOW,
        }
        if request.agent_type in enabled_agent_types - {AgentType.WORKFLOW} and not self._settings.rag_enabled:
            return RuntimeOutcome(failure=self._not_configured_problem())
        if request.agent_type not in enabled_agent_types:
            return RuntimeOutcome(
                failure=Problem(
                    title="Agent type is not enabled",
                    status=503,
                    code="agent_type_not_enabled",
                    detail="The requested Agent type is not enabled during the current rollout.",
                )
            )

        try:
            if self._graph is None:
                raise RuntimeError("LangGraph runtime has not been initialized")
            if request.agent_type == AgentType.KNOWLEDGE:
                await self._ensure_rag_dependencies()
            elif request.agent_type in {AgentType.DATA_ANALYSIS, AgentType.BUSINESS}:
                await self._ensure_answer_generator()
            state = await self._graph.ainvoke(
                {
                    "run_id": run_id,
                    "agent_type": request.agent_type.value,
                    "input": request.input,
                    "messages": [],
                    "delegated_authorization": delegated_authorization,
                    "tenant_id": request.access.tenant_id,
                    "locale": request.options.locale,
                    "knowledge_base_ids": tuple(sorted(request.access.allowed_knowledge_base_ids)),
                },
                config={"configurable": {"thread_id": run_id}},
            )
            interrupt_value = self._interrupt_value(state)
            if interrupt_value is not None:
                return RuntimeOutcome(waiting_approval_id=self._approval_id(interrupt_value, run_id))
            answer = state.get("answer")
            if not isinstance(answer, str) or not answer.strip():
                raise RuntimeError("LangGraph completed without an answer")
            return RuntimeOutcome(answer=answer, sources=tuple(state.get("sources", ())))
        except RagConfigurationError as error:
            return RuntimeOutcome(
                failure=Problem(
                    title="Agent runtime is misconfigured",
                    status=503,
                    code="rag_runtime_misconfigured",
                    detail=str(error),
                )
            )
        except RagAuthorizationError as error:
            return RuntimeOutcome(
                failure=Problem(
                    title="RAG tenant scope is unavailable",
                    status=503,
                    code="rag_tenant_scope_not_configured",
                    detail=str(error),
                )
            )
        except BusinessToolError as error:
            return RuntimeOutcome(
                failure=Problem(
                    title="Business Tool invocation failed",
                    status=error.status,
                    code=error.code,
                    detail=error.detail,
                )
            )
        except RagGenerationError:
            return RuntimeOutcome(
                failure=Problem(
                    title="Language model invocation failed",
                    status=502,
                    code="llm_provider_failed",
                    detail="The configured language model could not complete the Agent response.",
                )
            )
        except RagRetrievalError:
            return RuntimeOutcome(
                failure=Problem(
                    title="Knowledge retrieval failed",
                    status=503,
                    code="rag_retrieval_failed",
                    detail="Authorized knowledge retrieval is temporarily unavailable.",
                )
            )

    async def resume(
        self,
        request: AgentRunRequest,
        run_id: str,
        decision: ApprovalDecision,
        delegated_authorization: str,
    ) -> RuntimeOutcome:
        if self._graph is None:
            return RuntimeOutcome(
                failure=Problem(
                    title="Agent runtime is not initialized",
                    status=503,
                    code="agent_runtime_not_initialized",
                )
            )
        try:
            state = await self._graph.ainvoke(
                Command(
                    update={"delegated_authorization": delegated_authorization},
                    resume=decision.model_dump(by_alias=True, mode="json", exclude_none=True),
                ),
                config={"configurable": {"thread_id": run_id}},
            )
            interrupt_value = self._interrupt_value(state)
            if interrupt_value is not None:
                return RuntimeOutcome(waiting_approval_id=self._approval_id(interrupt_value, run_id))
            answer = state.get("answer")
            if not isinstance(answer, str) or not answer.strip():
                raise RuntimeError("LangGraph resume completed without an answer")
            return RuntimeOutcome(answer=answer, sources=tuple(state.get("sources", ())))
        except (RuntimeError, ValueError, KeyError, TypeError):
            return RuntimeOutcome(
                failure=Problem(
                    title="Workflow resume failed",
                    status=500,
                    code="workflow_resume_failed",
                    detail="The workflow could not resume from its approval checkpoint.",
                )
            )

    async def readiness(self) -> Problem | None:
        if not self._settings.rag_enabled:
            return self._not_configured_problem()
        try:
            await self._ensure_rag_dependencies()
        except RagConfigurationError:
            return Problem(
                title="Agent runtime is misconfigured",
                status=503,
                code="rag_runtime_misconfigured",
                detail="One or more required RAG dependencies are not configured.",
            )
        return None

    async def aclose(self) -> None:
        try:
            close = getattr(self._rag_retriever, "aclose", None)
            if close is not None:
                await close()
        finally:
            try:
                await self._checkpoint_store.aclose()
            finally:
                await self._http_client.aclose()

    async def initialize(self) -> None:
        await self._checkpoint_store.initialize()
        self._compile_graph_if_available()

    def _compile_graph_if_available(self) -> None:
        if self._graph is None:
            try:
                checkpointer = self._checkpoint_store.checkpointer
            except RuntimeError:
                return
            self._graph = self._graph_definition.compile(checkpointer=checkpointer)

    async def _ensure_rag_dependencies(self) -> None:
        if self._rag_retriever is not None and self._answer_generator is not None:
            return
        async with self._rag_initialization_lock:
            if self._rag_retriever is None:
                self._rag_retriever = create_retriever(self._settings)
            if self._answer_generator is None:
                self._answer_generator = create_answer_generator(self._settings)

    async def _ensure_answer_generator(self) -> None:
        if self._answer_generator is not None:
            return
        async with self._rag_initialization_lock:
            if self._answer_generator is None:
                self._answer_generator = create_answer_generator(self._settings)

    @staticmethod
    def _workflow_approval(state: AgentGraphState) -> dict[str, Any]:
        approval_id = f"{state['run_id']}-approval"
        decision = interrupt({"approvalId": approval_id, "reason": "Workflow requires approval before continuing."})
        if isinstance(decision, dict) and decision.get("decision") == "reject":
            answer = "Workflow approval rejected."
        else:
            answer = "Workflow approval accepted."
        return {"workflow_decision": decision, "answer": answer}

    @staticmethod
    def _interrupt_value(state: dict[str, Any]) -> Any | None:
        interrupts = state.get("__interrupt__")
        if not interrupts:
            return None
        return interrupts[0].value if hasattr(interrupts[0], "value") else interrupts[0]

    @staticmethod
    def _approval_id(value: Any, run_id: str) -> str:
        if isinstance(value, dict) and isinstance(value.get("approvalId"), str):
            return value["approvalId"]
        return f"{run_id}-approval"

    @staticmethod
    def _not_configured_problem() -> Problem:
        return Problem(
            title="Agent runtime is not configured",
            status=503,
            code="agent_runtime_not_configured",
            detail="RAG retrieval and LLM routing are not enabled yet.",
        )

    @staticmethod
    def _prepare_run(state: AgentGraphState) -> dict[str, Any]:
        return {
            "messages": [HumanMessage(content=state["input"])],
        }

    @staticmethod
    def _request_knowledge_bases(state: AgentGraphState) -> dict[str, Any]:
        return {
            "messages": [
                AIMessage(
                    content="",
                    tool_calls=[
                        {
                            "name": "knowledge_base_list",
                            "args": {"knowledge_base_ids": list(state["knowledge_base_ids"])},
                            "id": f"{state['run_id']}-knowledge-base-list",
                            "type": "tool_call",
                        }
                    ],
                )
            ]
        }

    @staticmethod
    def _request_dashboard_overview(state: AgentGraphState) -> dict[str, Any]:
        return {
            "messages": [
                AIMessage(
                    content="",
                    tool_calls=[
                        {
                            "name": "dashboard_overview",
                            "args": {"window": "7d"},
                            "id": f"{state['run_id']}-dashboard-overview",
                            "type": "tool_call",
                        }
                    ],
                )
            ]
        }

    @staticmethod
    def _request_business_overview(state: AgentGraphState) -> dict[str, Any]:
        args: dict[str, Any] = {}
        ids = state.get("knowledge_base_ids") or ()
        if ids:
            args["knowledge_base_ids"] = list(ids)
        return {
            "messages": [
                AIMessage(
                    content="",
                    tool_calls=[
                        {
                            "name": "knowledge_document_overview",
                            "args": args,
                            "id": f"{state['run_id']}-business-overview",
                            "type": "tool_call",
                        }
                    ],
                )
            ]
        }

    async def _retrieve_context(self, state: AgentGraphState) -> dict[str, Any]:
        if self._rag_retriever is None:
            raise RagConfigurationError("RAG retriever has not been initialized")
        knowledge_bases = self._knowledge_bases_from_tool(state)
        try:
            context = await self._rag_retriever.retrieve(
                RagRetrievalRequest(
                    query=state["input"],
                    tenant_id=state.get("tenant_id"),
                    knowledge_bases=knowledge_bases,
                )
            )
        except (RagAuthorizationError, RagConfigurationError):
            raise
        except Exception as error:
            raise RagRetrievalError("Authorized knowledge retrieval failed") from error
        return {
            "knowledge_bases": knowledge_bases,
            "rag_context": context.prompt_context,
            "sources": tuple(source.model_dump(by_alias=True, exclude_none=True) for source in context.sources),
        }

    async def _generate_answer(self, state: AgentGraphState) -> dict[str, Any]:
        context = state.get("rag_context", "")
        if not context:
            return {"answer": self._no_knowledge_answer(state.get("locale", "zh-CN"))}
        if self._answer_generator is None:
            raise RagConfigurationError("RAG answer generator has not been initialized")
        try:
            answer = await self._answer_generator.generate(
                question=state["input"],
                context=context,
                locale=state.get("locale", "zh-CN"),
            )
        except RagConfigurationError:
            raise
        except Exception as error:
            raise RagGenerationError("Language model invocation failed") from error
        return {"answer": answer}

    async def _generate_dashboard_analysis(self, state: AgentGraphState) -> dict[str, Any]:
        if self._answer_generator is None:
            raise RagConfigurationError("Answer generator has not been initialized")
        context = self._dashboard_context_from_tool(state)
        try:
            answer = await self._answer_generator.generate_data_analysis(
                question=state["input"],
                context=context,
                locale=state.get("locale", "zh-CN"),
            )
        except RagConfigurationError:
            raise
        except Exception as error:
            raise RagGenerationError("Dashboard analysis generation failed") from error
        return {"dashboard_context": context, "answer": answer}

    async def _generate_business_analysis(self, state: AgentGraphState) -> dict[str, Any]:
        if self._answer_generator is None:
            raise RagConfigurationError("Answer generator has not been initialized")
        context = self._business_context_from_tool(state)
        try:
            answer = await self._answer_generator.generate_data_analysis(
                question=state["input"],
                context=context,
                locale=state.get("locale", "zh-CN"),
            )
        except RagConfigurationError:
            raise
        except Exception as error:
            raise RagGenerationError("Business overview generation failed") from error
        return {"business_context": context, "answer": answer}

    @staticmethod
    def _agent_path(state: AgentGraphState) -> str:
        return state["agent_type"]

    @staticmethod
    def _after_tool_path(state: AgentGraphState) -> str:
        return state["agent_type"]

    @staticmethod
    def _knowledge_bases_from_tool(state: AgentGraphState) -> tuple[KnowledgeBase, ...]:
        message = next((item for item in reversed(state["messages"]) if isinstance(item, ToolMessage)), None)
        if message is None or not isinstance(message.content, str):
            raise RuntimeError("Knowledge base Tool did not return a response")
        payload = json.loads(message.content)
        raw_knowledge_bases = payload.get("knowledgeBases")
        if not isinstance(raw_knowledge_bases, list):
            raise TypeError("Knowledge base Tool response has no knowledgeBases list")
        knowledge_bases = tuple(KnowledgeBase.model_validate(item) for item in raw_knowledge_bases)
        allowed = set(state["knowledge_base_ids"])
        if any(knowledge_base.id not in allowed for knowledge_base in knowledge_bases):
            raise RuntimeError("Knowledge base Tool returned a resource outside the delegated scope")
        return knowledge_bases

    @staticmethod
    def _dashboard_context_from_tool(state: AgentGraphState) -> str:
        message = next((item for item in reversed(state["messages"]) if isinstance(item, ToolMessage)), None)
        if message is None or not isinstance(message.content, str):
            raise RuntimeError("Dashboard Tool did not return a response")
        payload = json.loads(message.content)
        if not isinstance(payload, dict):
            raise TypeError("Dashboard Tool response must be an object")
        return json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True)

    @staticmethod
    def _business_context_from_tool(state: AgentGraphState) -> str:
        message = next((item for item in reversed(state["messages"]) if isinstance(item, ToolMessage)), None)
        if message is None or not isinstance(message.content, str):
            raise RuntimeError("Business Tool did not return a response")
        payload = json.loads(message.content)
        if not isinstance(payload, dict):
            raise TypeError("Business Tool response must be an object")
        return json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True)

    @staticmethod
    def _no_knowledge_answer(locale: str) -> str:
        if locale.lower().startswith("zh"):
            return "\u672a\u5728\u5f53\u524d\u83b7\u6388\u6743\u77e5\u8bc6\u5e93\u4e2d\u68c0\u7d22\u5230\u53ef\u7528\u4fe1\u606f\u3002"
        return "No relevant information was found in the currently authorized knowledge bases."
