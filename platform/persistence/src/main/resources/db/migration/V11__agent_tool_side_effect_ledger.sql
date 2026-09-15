CREATE TABLE IF NOT EXISTS agent.tool_side_effect_ledger (
    ledger_id UUID PRIMARY KEY,
    schema_version INTEGER NOT NULL,
    session_id VARCHAR(128) NOT NULL REFERENCES agent.sessions(session_id),
    request_id VARCHAR(128) NOT NULL,
    turn_id VARCHAR(128) NOT NULL,
    attempt_id UUID NOT NULL,
    step INTEGER NOT NULL,
    call_index INTEGER NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    tool_name VARCHAR(160) NOT NULL,
    tool_schema_version VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    status VARCHAR(24) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    result_digest VARCHAR(64),
    fencing_token BIGINT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT agent_side_effect_tool_call_unique UNIQUE (tool_call_id),
    CONSTRAINT agent_side_effect_idempotency_unique UNIQUE (idempotency_key),
    CONSTRAINT agent_side_effect_status_check CHECK (
        status IN ('PREPARED', 'EXECUTING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'RECONCILED')
    ),
    CONSTRAINT agent_side_effect_result_check CHECK (
        (status IN ('SUCCEEDED', 'FAILED', 'RECONCILED') AND result_digest IS NOT NULL)
        OR (status IN ('PREPARED', 'EXECUTING', 'UNKNOWN') AND result_digest IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS agent_side_effect_request_idx
    ON agent.tool_side_effect_ledger (session_id, request_id, step, call_index);

CREATE INDEX IF NOT EXISTS agent_side_effect_status_idx
    ON agent.tool_side_effect_ledger (status, updated_at);
