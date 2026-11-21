-- v1.1.0 260810 Agent runtime run/event persistence
-- Delegated authorization is intentionally not persisted in this schema.

CREATE SCHEMA IF NOT EXISTS ragent_agent;

CREATE TABLE IF NOT EXISTS ragent_agent.runs (
    run_id TEXT PRIMARY KEY,
    tenant_id TEXT,
    user_id TEXT NOT NULL,
    request JSONB NOT NULL,
    request_fingerprint TEXT NOT NULL,
    status TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    waiting_approval_id TEXT,
    error JSONB
);

ALTER TABLE ragent_agent.runs
    ADD COLUMN IF NOT EXISTS waiting_approval_id TEXT;

CREATE INDEX IF NOT EXISTS idx_ragent_agent_runs_active
    ON ragent_agent.runs (status, updated_at)
    WHERE status IN ('running', 'waiting_approval');

CREATE TABLE IF NOT EXISTS ragent_agent.idempotency (
    tenant_key TEXT NOT NULL,
    user_id TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    run_id TEXT NOT NULL REFERENCES ragent_agent.runs(run_id) ON DELETE CASCADE,
    request_fingerprint TEXT NOT NULL,
    PRIMARY KEY (tenant_key, user_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS ragent_agent.events (
    run_id TEXT NOT NULL REFERENCES ragent_agent.runs(run_id) ON DELETE CASCADE,
    sequence INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    event_timestamp TIMESTAMPTZ NOT NULL,
    data JSONB NOT NULL,
    PRIMARY KEY (run_id, sequence)
);

CREATE TABLE IF NOT EXISTS ragent_agent.approval_idempotency (
    tenant_key TEXT NOT NULL,
    user_id TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    run_id TEXT NOT NULL REFERENCES ragent_agent.runs(run_id) ON DELETE CASCADE,
    decision_fingerprint TEXT NOT NULL,
    PRIMARY KEY (tenant_key, user_id, idempotency_key)
);
