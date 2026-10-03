CREATE TABLE agent.session_snapshots (
    session_id VARCHAR(128) NOT NULL REFERENCES agent.sessions(session_id),
    event_position BIGINT NOT NULL CHECK (event_position > 0),
    schema_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (session_id, event_position)
);

CREATE INDEX agent_workspace_execution_facts_idx
    ON agent.workspace_events (session_id, request_id, event_position)
    WHERE event_type IN ('ASSISTANT_MESSAGE', 'TOOL_RESULT_MESSAGE', 'EXECUTION_STEP',
                         'EXECUTION_PROGRESS', 'EXECUTION_METADATA', 'EXECUTION_TERMINAL');

CREATE UNIQUE INDEX agent_workspace_tool_result_unique
    ON agent.workspace_events (session_id, tool_call_id)
    WHERE event_type = 'TOOL_RESULT_MESSAGE';

COMMENT ON TABLE agent.session_snapshots IS
    'State baseline and pending event references. Original event/message bodies remain append-only in workspace_events.';
