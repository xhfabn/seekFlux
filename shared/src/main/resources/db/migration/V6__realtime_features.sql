CREATE SCHEMA IF NOT EXISTS feature;

CREATE TABLE IF NOT EXISTS feature.realtime_events (
    event_id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    content_id UUID NOT NULL,
    content_tags JSONB NOT NULL,
    event_time TIMESTAMPTZ NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL,
    disposition VARCHAR(24) NOT NULL,
    rejection_reason VARCHAR(64),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT feature_event_disposition_check CHECK (
        disposition IN ('RECEIVED', 'APPLIED', 'LATE_DROPPED')
    )
);

CREATE INDEX IF NOT EXISTS feature_events_user_time_idx
    ON feature.realtime_events (user_id, event_time DESC)
    WHERE disposition = 'APPLIED';

CREATE INDEX IF NOT EXISTS feature_events_content_time_idx
    ON feature.realtime_events (content_id, event_time DESC)
    WHERE disposition = 'APPLIED';

CREATE TABLE IF NOT EXISTS feature.stream_watermarks (
    stream_key VARCHAR(80) PRIMARY KEY,
    max_event_time TIMESTAMPTZ NOT NULL,
    watermark TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS feature.short_term_interest_snapshots (
    user_id VARCHAR(128) PRIMARY KEY,
    topics JSONB NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL,
    feature_version VARCHAR(80) NOT NULL,
    source_event_id UUID NOT NULL
);

CREATE TABLE IF NOT EXISTS feature.content_heat_snapshots (
    content_id UUID PRIMARY KEY,
    score DOUBLE PRECISION NOT NULL,
    event_count BIGINT NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL,
    feature_version VARCHAR(80) NOT NULL,
    source_event_id UUID NOT NULL
);

CREATE INDEX IF NOT EXISTS feature_content_heat_score_idx
    ON feature.content_heat_snapshots (score DESC, computed_at DESC);
