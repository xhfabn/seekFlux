ALTER TABLE agent.sessions
    ADD COLUMN IF NOT EXISTS active_fencing_token BIGINT NOT NULL DEFAULT 0;

ALTER TABLE agent.runs
    DROP CONSTRAINT IF EXISTS agent_run_request_turn_unique;

CREATE INDEX IF NOT EXISTS agent_runs_request_attempt_idx
    ON agent.runs (request_id, session_id, turn_id, started_at DESC);

CREATE TABLE IF NOT EXISTS agent.audit_events (
    event_id UUID PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    agent_run_id UUID NOT NULL,
    event_type VARCHAR(160) NOT NULL,
    event_time TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS agent_audit_events_session_time_idx
    ON agent.audit_events (session_id, event_time DESC);

CREATE TABLE IF NOT EXISTS agent.shadow_evaluations (
    evaluation_id UUID PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    step INTEGER NOT NULL,
    primary_version VARCHAR(160) NOT NULL,
    shadow_version VARCHAR(160) NOT NULL,
    primary_decision VARCHAR(80) NOT NULL,
    shadow_decision VARCHAR(80),
    agreed BOOLEAN NOT NULL,
    shadow_took_ms BIGINT NOT NULL,
    error_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS agent_shadow_evaluations_created_idx
    ON agent.shadow_evaluations (created_at DESC);
