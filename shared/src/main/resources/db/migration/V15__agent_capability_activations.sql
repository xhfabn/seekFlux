ALTER TABLE agent.sessions
    ADD COLUMN IF NOT EXISTS capability_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE agent.sessions
    ADD CONSTRAINT agent_sessions_capability_version_nonnegative
    CHECK (capability_version >= 0);

CREATE UNIQUE INDEX IF NOT EXISTS agent_capability_operation_unique
    ON agent.workspace_events (session_id, ((payload ->> 'operationId')))
    WHERE event_type = 'CAPABILITIES_CHANGED';
