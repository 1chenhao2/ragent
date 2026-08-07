from __future__ import annotations

from dataclasses import dataclass
from typing import TypedDict

from langchain_core.messages import BaseMessage, HumanMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, StateGraph

from ragent_agent.contracts.models import AgentRunRequest, Problem


class AgentGraphState(TypedDict):
    run_id: str
    agent_type: str
    input: str
    messages: list[BaseMessage]
    stage: str


@dataclass(frozen=True)
class RuntimeOutcome:
    failure: Problem | None = None


class LangGraphAgentRuntime:
    def __init__(self) -> None:
        graph = StateGraph(AgentGraphState)
        graph.add_node("prepare_run", self._prepare_run)
        graph.add_edge(START, "prepare_run")
        graph.add_edge("prepare_run", END)
        self._graph = graph.compile(checkpointer=InMemorySaver())

    async def execute(self, request: AgentRunRequest, run_id: str) -> RuntimeOutcome:
        await self._graph.ainvoke(
            {
                "run_id": run_id,
                "agent_type": request.agent_type,
                "input": request.input,
                "messages": [],
                "stage": "accepted",
            },
            config={"configurable": {"thread_id": run_id}},
        )
        return RuntimeOutcome(
            failure=Problem(
                title="Agent runtime is not configured",
                status=503,
                code="agent_runtime_not_configured",
                detail="Knowledge tools and RAG retrieval are introduced in later migration stages.",
            )
        )

    @staticmethod
    def _prepare_run(state: AgentGraphState) -> AgentGraphState:
        return {
            "messages": [HumanMessage(content=state["input"])],
            "stage": "awaiting_agent_configuration",
        }
