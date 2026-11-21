from __future__ import annotations

import time
from datetime import UTC, datetime, timedelta

import jwt
import pytest
from fastapi.testclient import TestClient

from ragent_agent.api.app import create_app
from ragent_agent.config import AgentServiceSettings

TEST_SECRET = "test-delegated-token-secret-0123456789"


def make_headers(
    user_id: str = "user-1",
    tenant_id: str = "tenant-1",
    idempotency_key: str = "idempotency-key-0001",
) -> dict[str, str]:
    now = datetime.now(UTC)
    token = jwt.encode(
        {
            "sub": user_id,
            "iss": "ragent-java",
            "aud": "ragent-agent-service",
            "iat": now,
            "exp": now + timedelta(minutes=5),
            "tenantId": tenant_id,
            "roles": ["user"],
            "allowedKnowledgeBaseIds": ["knowledge-1"],
            "toolScopes": ["knowledge:read"],
        },
        TEST_SECRET,
        algorithm="HS256",
    )
    return {
        "Authorization": f"Bearer {token}",
        "Idempotency-Key": idempotency_key,
    }


def make_run_request(
    input_text: str = "Summarize the knowledge base.",
    tenant_id: str = "tenant-1",
) -> dict[str, object]:
    return {
        "requestId": "request-1",
        "conversationId": "conversation-1",
        "agentType": "knowledge",
        "input": input_text,
        "access": {
            "tenantId": tenant_id,
            "userId": "user-1",
            "roles": ["user"],
            "allowedKnowledgeBaseIds": ["knowledge-1"],
            "toolScopes": ["knowledge:read"],
        },
        "options": {
            "deepThinking": False,
            "locale": "zh-CN",
        },
    }


@pytest.fixture
def client() -> TestClient:
    settings = AgentServiceSettings(
        delegated_token_hmac_secret=TEST_SECRET,
        delegated_token_issuer="ragent-java",
        delegated_token_audience="ragent-agent-service",
        auto_start_runs=False,
    )
    return TestClient(create_app(settings))


def test_health_is_available_without_delegated_token(client: TestClient) -> None:
    response = client.get("/internal/v1/health")

    assert response.status_code == 200
    assert response.json()["status"] == "ready"


def test_readiness_rejects_traffic_when_rag_runtime_is_disabled(client: TestClient) -> None:
    response = client.get("/internal/v1/ready")

    assert response.status_code == 503
    assert response.json()["code"] == "agent_runtime_not_configured"


def test_readiness_accepts_traffic_when_rag_adapters_are_configured() -> None:
    settings = AgentServiceSettings(
        delegated_token_hmac_secret=TEST_SECRET,
        delegated_token_issuer="ragent-java",
        delegated_token_audience="ragent-agent-service",
        rag_enabled=True,
        rag_pg_dsn="postgresql://user:password@postgres/ragent",
        rag_embedding_base_url="https://embedding.example.test/v1",
        rag_embedding_api_key="placeholder",
        rag_chat_base_url="https://chat.example.test/v1",
        rag_chat_api_key="placeholder",
        rag_chat_model="test-model",
    )

    with TestClient(create_app(settings)) as ready_client:
        response = ready_client.get("/internal/v1/ready")

    assert response.status_code == 200
    assert response.json()["status"] == "ready"


def test_agent_run_requires_a_delegated_token(client: TestClient) -> None:
    response = client.post(
        "/internal/v1/agent-runs",
        headers={"Idempotency-Key": "idempotency-key-0001"},
        json=make_run_request(),
    )

    assert response.status_code == 401


def test_agent_run_is_idempotent_for_the_same_user_and_payload(client: TestClient) -> None:
    headers = make_headers()
    payload = make_run_request()

    first = client.post("/internal/v1/agent-runs", headers=headers, json=payload)
    second = client.post("/internal/v1/agent-runs", headers=headers, json=payload)

    assert first.status_code == 202
    assert second.status_code == 202
    assert first.json()["runId"] == second.json()["runId"]
    assert first.json()["eventStreamUrl"] == second.json()["eventStreamUrl"]


def test_agent_run_rejects_idempotency_key_reuse_with_a_different_payload(client: TestClient) -> None:
    headers = make_headers()

    first = client.post("/internal/v1/agent-runs", headers=headers, json=make_run_request())
    second = client.post(
        "/internal/v1/agent-runs",
        headers=headers,
        json=make_run_request("Use a different instruction."),
    )

    assert first.status_code == 202
    assert second.status_code == 409
    assert second.json()["code"] == "idempotency_conflict"


def test_agent_run_idempotency_is_isolated_to_the_delegated_tenant(client: TestClient) -> None:
    tenant_one = client.post(
        "/internal/v1/agent-runs",
        headers=make_headers(),
        json=make_run_request(),
    )
    tenant_two = client.post(
        "/internal/v1/agent-runs",
        headers=make_headers(tenant_id="tenant-2"),
        json=make_run_request(tenant_id="tenant-2"),
    )

    assert tenant_one.status_code == 202
    assert tenant_two.status_code == 202
    assert tenant_one.json()["runId"] != tenant_two.json()["runId"]


def test_agent_run_state_is_isolated_to_the_delegated_user(client: TestClient) -> None:
    created = client.post(
        "/internal/v1/agent-runs",
        headers=make_headers(),
        json=make_run_request(),
    )

    response = client.get(
        f"/internal/v1/agent-runs/{created.json()['runId']}",
        headers=make_headers(user_id="user-2"),
    )

    assert created.status_code == 202
    assert response.status_code == 403


def test_agent_run_state_is_isolated_to_the_delegated_tenant(client: TestClient) -> None:
    created = client.post(
        "/internal/v1/agent-runs",
        headers=make_headers(),
        json=make_run_request(),
    )

    response = client.get(
        f"/internal/v1/agent-runs/{created.json()['runId']}",
        headers=make_headers(tenant_id="tenant-2"),
    )

    assert created.status_code == 202
    assert response.status_code == 403


def test_cancel_publishes_one_terminal_event(client: TestClient) -> None:
    created = client.post(
        "/internal/v1/agent-runs",
        headers=make_headers(),
        json=make_run_request(),
    )
    run_id = created.json()["runId"]

    cancelled = client.post(
        f"/internal/v1/agent-runs/{run_id}/cancel",
        headers=make_headers(idempotency_key="idempotency-key-0002"),
    )
    events = client.get(
        f"/internal/v1/agent-runs/{run_id}/events",
        headers=make_headers(idempotency_key="idempotency-key-0003"),
    )

    assert cancelled.status_code == 202
    assert cancelled.json()["status"] == "cancelled"
    assert events.status_code == 200
    assert "event: run_started" in events.text
    assert events.text.count("event: run_cancelled") == 1


def test_event_stream_rejects_cross_user_access_before_starting_the_stream(client: TestClient) -> None:
    created = client.post(
        "/internal/v1/agent-runs",
        headers=make_headers(),
        json=make_run_request(),
    )
    run_id = created.json()["runId"]

    response = client.get(
        f"/internal/v1/agent-runs/{run_id}/events",
        headers=make_headers(user_id="user-2"),
    )

    assert response.status_code == 403
    assert response.headers["content-type"].startswith("application/problem+json")


def test_metrics_expose_low_cardinality_agent_run_outcomes() -> None:
    settings = AgentServiceSettings(
        delegated_token_hmac_secret=TEST_SECRET,
        delegated_token_issuer="ragent-java",
        delegated_token_audience="ragent-agent-service",
        auto_start_runs=True,
    )

    with TestClient(create_app(settings)) as metrics_client:
        created = metrics_client.post(
            "/internal/v1/agent-runs",
            headers=make_headers(),
            json=make_run_request(),
        )
        for _ in range(50):
            state = metrics_client.get(
                f"/internal/v1/agent-runs/{created.json()['runId']}",
                headers=make_headers(),
            )
            if state.json()["status"] == "failed":
                break
            time.sleep(0.01)
        response = metrics_client.get("/internal/v1/metrics")

    assert response.status_code == 200
    assert 'ragent_agent_runs_total{agent_type="knowledge",status="failed"} 1.0' in response.text
    assert 'ragent_agent_active_runs{agent_type="knowledge"} 0.0' in response.text
