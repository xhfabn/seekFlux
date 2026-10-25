ALTER TABLE agent.tool_call_journal
    DROP CONSTRAINT IF EXISTS agent_tool_journal_status_check;

ALTER TABLE agent.tool_call_journal
    ADD CONSTRAINT agent_tool_journal_status_check CHECK (
        status IN (
            'DECIDED', 'EXECUTING', 'UNKNOWN', 'WAITING',
            'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'
        )
    );

ALTER TABLE agent.runs
    DROP CONSTRAINT IF EXISTS agent_run_state_check;

ALTER TABLE agent.runs
    ADD CONSTRAINT agent_run_state_check CHECK (
        state IN (
            'RUNNING', 'RESULTS_READY', 'NEED_CLARIFICATION', 'WAITING',
            'FALLBACK_REQUIRED', 'CANCELLED', 'FAILED'
        )
    );

CREATE TABLE IF NOT EXISTS agent.runtime_waits (
    wait_id UUID PRIMARY KEY,
    schema_version INTEGER NOT NULL,
    session_id VARCHAR(128) NOT NULL REFERENCES agent.sessions(session_id),
    request_id VARCHAR(128) NOT NULL,
    turn_id VARCHAR(128) NOT NULL,
    checkpoint_id UUID REFERENCES agent.runtime_checkpoints(checkpoint_id)
        ON UPDATE CASCADE ON DELETE SET NULL,
    tool_call_id VARCHAR(128) REFERENCES agent.tool_call_journal(tool_call_id) ON DELETE SET NULL,
    wait_type VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    resolution_id VARCHAR(128),
    deadline_at TIMESTAMPTZ NOT NULL,
    fencing_token BIGINT NOT NULL,
    payload JSONB NOT NULL,
    resolution JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT agent_runtime_wait_type_check CHECK (
        wait_type IN ('HITL', 'ASYNC_TASK', 'WAITPOINT', 'HANDOFF', 'CHILD_AGENT')
    ),
    CONSTRAINT agent_runtime_wait_status_check CHECK (
        status IN (
            'PENDING', 'APPROVED', 'DENIED', 'COMPLETED',
            'TIMED_OUT', 'CANCELLED', 'FAILED'
        )
    ),
    CONSTRAINT agent_runtime_wait_resolution_shape_check CHECK (
        (status = 'PENDING' AND resolution_id IS NULL AND resolution IS NULL)
        OR
        (status <> 'PENDING' AND resolution_id IS NOT NULL AND resolution IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS agent_runtime_wait_one_pending_per_session_idx
    ON agent.runtime_waits (session_id)
    WHERE status = 'PENDING';

CREATE UNIQUE INDEX IF NOT EXISTS agent_runtime_wait_resolution_id_idx
    ON agent.runtime_waits (resolution_id)
    WHERE resolution_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS agent_runtime_wait_expiry_idx
    ON agent.runtime_waits (deadline_at, wait_id)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS agent_runtime_wait_child_session_idx
    ON agent.runtime_waits (session_id, request_id, updated_at DESC);
