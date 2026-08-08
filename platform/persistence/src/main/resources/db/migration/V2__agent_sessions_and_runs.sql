CREATE SCHEMA IF NOT EXISTS agent;

CREATE TABLE IF NOT EXISTS agent.sessions (
    session_id VARCHAR(128) PRIMARY KEY,
    agent_id VARCHAR(128) NOT NULL,
    agent_version VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    event_position BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'IDLE',
    last_turn_id VARCHAR(128),
    last_agent_run_id UUID,
    snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS agent.workspace_events (
    event_id UUID PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL REFERENCES agent.sessions(session_id),
    event_position BIGINT NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    request_id VARCHAR(128),
    turn_id VARCHAR(128),
    event_time TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    CONSTRAINT agent_workspace_event_position_unique UNIQUE (session_id, event_position),
    CONSTRAINT agent_workspace_event_request_unique UNIQUE (session_id, request_id)
);

CREATE INDEX IF NOT EXISTS agent_workspace_events_session_position_idx
    ON agent.workspace_events (session_id, event_position);

CREATE TABLE IF NOT EXISTS agent.runs (
    agent_run_id UUID PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL REFERENCES agent.sessions(session_id),
    turn_id VARCHAR(128) NOT NULL,
    agent_definition JSONB NOT NULL,
    state VARCHAR(40) NOT NULL,
    execution_mode VARCHAR(48),
    fallback_reason VARCHAR(128),
    trace JSONB,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT agent_run_state_check CHECK (
        state IN ('RUNNING', 'RESULTS_READY', 'NEED_CLARIFICATION', 'FALLBACK_REQUIRED', 'CANCELLED', 'FAILED')
    ),
    CONSTRAINT agent_run_request_turn_unique UNIQUE (request_id, session_id, turn_id)
);

CREATE INDEX IF NOT EXISTS agent_runs_session_started_idx
    ON agent.runs (session_id, started_at DESC);

CREATE TABLE IF NOT EXISTS agent.run_events (
    event_id UUID PRIMARY KEY,
    agent_run_id UUID NOT NULL REFERENCES agent.runs(agent_run_id),
    event_sequence INTEGER NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    event_time TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    CONSTRAINT agent_run_event_sequence_unique UNIQUE (agent_run_id, event_sequence)
);

CREATE INDEX IF NOT EXISTS agent_run_events_run_sequence_idx
    ON agent.run_events (agent_run_id, event_sequence);
