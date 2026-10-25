DROP INDEX IF EXISTS agent.agent_workspace_message_id_unique;

CREATE UNIQUE INDEX IF NOT EXISTS agent_workspace_message_type_id_unique
    ON agent.workspace_events (message_id, event_type)
    WHERE message_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS agent_workspace_queued_request_unique
    ON agent.workspace_events (session_id, request_id)
    WHERE event_type = 'QUEUED_USER_MESSAGE';

CREATE INDEX IF NOT EXISTS agent_workspace_queue_session_position_idx
    ON agent.workspace_events (session_id, event_position)
    WHERE event_type = 'QUEUED_USER_MESSAGE';
