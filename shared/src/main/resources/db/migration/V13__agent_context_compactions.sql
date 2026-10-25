CREATE TABLE IF NOT EXISTS agent.context_compactions (
    summary_id UUID PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL REFERENCES agent.sessions(session_id) ON DELETE CASCADE,
    schema_version INTEGER NOT NULL,
    strategy_version VARCHAR(128) NOT NULL,
    from_exclusive BIGINT NOT NULL,
    inclusive_cutoff BIGINT NOT NULL,
    summary TEXT NOT NULL,
    estimated_tokens INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT agent_context_compaction_schema_check CHECK (schema_version > 0),
    CONSTRAINT agent_context_compaction_range_check CHECK (
        from_exclusive >= 0 AND inclusive_cutoff > from_exclusive
    ),
    CONSTRAINT agent_context_compaction_tokens_check CHECK (estimated_tokens > 0),
    CONSTRAINT agent_context_compaction_cutoff_unique UNIQUE (
        session_id, inclusive_cutoff, strategy_version
    )
);

CREATE INDEX IF NOT EXISTS agent_context_compaction_latest_idx
    ON agent.context_compactions (session_id, inclusive_cutoff DESC, created_at DESC);
