CREATE SCHEMA IF NOT EXISTS content;
CREATE SCHEMA IF NOT EXISTS outbox;

CREATE TABLE IF NOT EXISTS content.contents (
    content_id UUID PRIMARY KEY,
    creator_id VARCHAR(128) NOT NULL,
    media_uri TEXT NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    source_tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL,
    profile_version INTEGER,
    profile_summary TEXT,
    profile_tags JSONB,
    profile_transcript TEXT,
    aggregate_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    withdrawn_at TIMESTAMPTZ,
    CONSTRAINT content_status_check CHECK (
        status IN ('SUBMITTED', 'PROFILE_READY', 'PUBLISHED', 'WITHDRAWN')
    ),
    CONSTRAINT content_profile_consistency CHECK (
        (status IN ('PROFILE_READY', 'PUBLISHED') AND profile_version IS NOT NULL AND profile_summary IS NOT NULL)
        OR status IN ('SUBMITTED', 'WITHDRAWN')
    )
);

CREATE INDEX IF NOT EXISTS contents_status_updated_idx
    ON content.contents (status, updated_at DESC);

CREATE TABLE IF NOT EXISTS outbox.events (
    event_id UUID PRIMARY KEY,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(160) NOT NULL,
    schema_version INTEGER NOT NULL,
    event_time TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    last_error TEXT,
    CONSTRAINT outbox_status_check CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED'))
);

CREATE INDEX IF NOT EXISTS outbox_pending_idx
    ON outbox.events (next_attempt_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS outbox_stale_claim_idx
    ON outbox.events (locked_at)
    WHERE status = 'PUBLISHING';
