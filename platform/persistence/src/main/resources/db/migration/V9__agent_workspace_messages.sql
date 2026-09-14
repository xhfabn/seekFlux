ALTER TABLE agent.workspace_events
    ADD COLUMN IF NOT EXISTS schema_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS message_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS tool_call_id VARCHAR(128);

ALTER TABLE agent.workspace_events
    DROP CONSTRAINT IF EXISTS agent_workspace_event_request_unique;

CREATE UNIQUE INDEX IF NOT EXISTS agent_workspace_user_request_unique
    ON agent.workspace_events (session_id, request_id)
    WHERE event_type = 'USER_MESSAGE';

CREATE UNIQUE INDEX IF NOT EXISTS agent_workspace_message_id_unique
    ON agent.workspace_events (message_id)
    WHERE message_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS agent_workspace_tool_call_idx
    ON agent.workspace_events (session_id, tool_call_id)
    WHERE tool_call_id IS NOT NULL;
