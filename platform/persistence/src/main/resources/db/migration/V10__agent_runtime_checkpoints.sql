CREATE TABLE IF NOT EXISTS agent.runtime_checkpoints (
    checkpoint_id UUID PRIMARY KEY,
    schema_version INTEGER NOT NULL,
    session_id VARCHAR(128) NOT NULL REFERENCES agent.sessions(session_id),
    request_id VARCHAR(128) NOT NULL,
    turn_id VARCHAR(128) NOT NULL,
    attempt_id UUID NOT NULL,
    boundary VARCHAR(32) NOT NULL,
    fencing_token BIGINT NOT NULL,
    message_cutoff BIGINT NOT NULL,
    next_step INTEGER NOT NULL,
    tool_call_count INTEGER NOT NULL,
    remaining_budget_ms BIGINT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT agent_runtime_checkpoint_request_unique UNIQUE (session_id, request_id),
    CONSTRAINT agent_runtime_checkpoint_boundary_check CHECK (
        boundary IN ('PRE_TURN', 'POST_TURN', 'COMPLETED', 'SUSPENDED')
    )
);

CREATE INDEX IF NOT EXISTS agent_runtime_checkpoint_session_idx
    ON agent.runtime_checkpoints (session_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS agent.tool_call_journal (
    tool_call_id VARCHAR(128) PRIMARY KEY,
    schema_version INTEGER NOT NULL,
    session_id VARCHAR(128) NOT NULL REFERENCES agent.sessions(session_id),
    request_id VARCHAR(128) NOT NULL,
    turn_id VARCHAR(128) NOT NULL,
    attempt_id UUID NOT NULL,
    step INTEGER NOT NULL,
    call_index INTEGER NOT NULL,
    tool_name VARCHAR(160) NOT NULL,
    tool_schema_version VARCHAR(160) NOT NULL,
    effect VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    arguments_digest VARCHAR(64) NOT NULL,
    fencing_token BIGINT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT agent_tool_journal_position_unique UNIQUE (session_id, request_id, step, call_index),
    CONSTRAINT agent_tool_journal_effect_check CHECK (
        effect IN ('READ_ONLY', 'IDEMPOTENT', 'MUTATING')
    ),
    CONSTRAINT agent_tool_journal_status_check CHECK (
        status IN ('DECIDED', 'EXECUTING', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT')
    )
);

CREATE INDEX IF NOT EXISTS agent_tool_journal_request_idx
    ON agent.tool_call_journal (session_id, request_id, step, call_index);
