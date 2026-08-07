from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class HealthResponse(ContractModel):
    status: Literal["ready"] = "ready"
    version: str | None = None


class AgentType(StrEnum):
    KNOWLEDGE = "knowledge"
    BUSINESS = "business"
    DATA_ANALYSIS = "data_analysis"
    WORKFLOW = "workflow"


class RunStatus(StrEnum):
    ACCEPTED = "accepted"
    RUNNING = "running"
    WAITING_APPROVAL = "waiting_approval"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class AgentEventType(StrEnum):
    RUN_STARTED = "run_started"
    THINKING_DELTA = "thinking_delta"
    ANSWER_DELTA = "answer_delta"
    SOURCES = "sources"
    TOOL_STARTED = "tool_started"
    TOOL_COMPLETED = "tool_completed"
    APPROVAL_REQUIRED = "approval_required"
    RUN_COMPLETED = "run_completed"
    RUN_FAILED = "run_failed"
    RUN_CANCELLED = "run_cancelled"
    HEARTBEAT = "heartbeat"


class ApprovalDecisionType(StrEnum):
    APPROVE = "approve"
    REJECT = "reject"
    PROVIDE_INPUT = "provide_input"


class AccessContext(ContractModel):
    tenant_id: str | None = Field(default=None, alias="tenantId")
    user_id: str = Field(min_length=1, alias="userId")
    roles: set[str]
    allowed_knowledge_base_ids: set[str] = Field(alias="allowedKnowledgeBaseIds")
    tool_scopes: set[str] = Field(alias="toolScopes")


class AgentRunOptions(ContractModel):
    deep_thinking: bool = Field(default=False, alias="deepThinking")
    locale: str = "zh-CN"
    deadline_ms: int | None = Field(default=None, alias="deadlineMs", ge=1000, le=900000)
    shadow: bool = False


class AgentRunRequest(ContractModel):
    request_id: str = Field(min_length=1, alias="requestId")
    conversation_id: str = Field(min_length=1, alias="conversationId")
    agent_type: AgentType = Field(alias="agentType")
    input: str = Field(min_length=1, max_length=32000)
    access: AccessContext
    options: AgentRunOptions = Field(default_factory=AgentRunOptions)
    metadata: dict[str, Any] = Field(default_factory=dict)


class Problem(ContractModel):
    type: str = "about:blank"
    title: str
    status: int
    detail: str | None = None
    code: str | None = None
    trace_id: str | None = Field(default=None, alias="traceId")


class AgentRunAccepted(ContractModel):
    run_id: str = Field(alias="runId")
    status: RunStatus
    event_stream_url: str = Field(alias="eventStreamUrl")


class AgentRunState(ContractModel):
    run_id: str = Field(alias="runId")
    status: RunStatus
    waiting_approval_id: str | None = Field(default=None, alias="waitingApprovalId")
    updated_at: datetime = Field(alias="updatedAt")
    error: Problem | None = None


class AgentEvent(ContractModel):
    run_id: str = Field(alias="runId")
    sequence: int = Field(ge=0)
    type: AgentEventType
    timestamp: datetime
    data: dict[str, Any]


class ApprovalDecision(ContractModel):
    approval_id: str = Field(min_length=1, alias="approvalId")
    decision: ApprovalDecisionType
    input: str | None = Field(default=None, max_length=32000)
