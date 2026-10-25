CREATE SCHEMA IF NOT EXISTS interaction;

CREATE TABLE IF NOT EXISTS interaction.batches (
    batch_id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    request_hash CHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    response JSONB,
    received_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS interaction.ingress_events (
    event_id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES interaction.batches(batch_id),
    user_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    content_id UUID NOT NULL,
    position INTEGER NOT NULL,
    surface VARCHAR(32) NOT NULL,
    event_time TIMESTAMPTZ NOT NULL,
    disposition VARCHAR(24) NOT NULL,
    rejection_reason VARCHAR(64),
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT interaction_type_check CHECK (
        event_type IN ('EXPOSURE', 'CLICK', 'PLAY_START', 'LIKE', 'SAVE', 'PLAY_COMPLETE', 'NOT_INTERESTED')
    ),
    CONSTRAINT interaction_surface_check CHECK (surface IN ('FEED', 'SEARCH', 'AGENT')),
    CONSTRAINT interaction_disposition_check CHECK (disposition IN ('ACCEPTED', 'REJECTED')),
    CONSTRAINT interaction_position_check CHECK (position BETWEEN 1 AND 10000),
    CONSTRAINT interaction_rejection_consistency CHECK (
        (disposition = 'ACCEPTED' AND rejection_reason IS NULL)
        OR (disposition = 'REJECTED' AND rejection_reason IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS interaction_attribution_idx
    ON interaction.ingress_events (
        user_id, request_id, trace_id, content_id, position, surface, event_type, event_time
    );

CREATE TABLE IF NOT EXISTS interaction.facts (
    event_id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    content_id UUID NOT NULL,
    position INTEGER NOT NULL,
    surface VARCHAR(32) NOT NULL,
    event_time TIMESTAMPTZ NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS interaction_facts_user_time_idx
    ON interaction.facts (user_id, event_time DESC);

CREATE INDEX IF NOT EXISTS interaction_facts_attribution_idx
    ON interaction.facts (request_id, trace_id, content_id, position);
