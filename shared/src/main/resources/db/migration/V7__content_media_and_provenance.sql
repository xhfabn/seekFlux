ALTER TABLE content.contents
    ADD COLUMN IF NOT EXISTS content_type VARCHAR(16) NOT NULL DEFAULT 'VIDEO',
    ADD COLUMN IF NOT EXISTS asset_uris JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS body TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS source_provider VARCHAR(64),
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(256),
    ADD COLUMN IF NOT EXISTS source_page_uri TEXT,
    ADD COLUMN IF NOT EXISTS source_author VARCHAR(128),
    ADD COLUMN IF NOT EXISTS license_name VARCHAR(128);

UPDATE content.contents
SET asset_uris = jsonb_build_array(media_uri)
WHERE asset_uris = '[]'::jsonb;

ALTER TABLE content.contents
    DROP CONSTRAINT IF EXISTS content_type_check;

ALTER TABLE content.contents
    ADD CONSTRAINT content_type_check CHECK (content_type IN ('VIDEO', 'ARTICLE')),
    ADD CONSTRAINT content_source_identity_check CHECK (
        (source_provider IS NULL AND external_id IS NULL)
        OR (source_provider IS NOT NULL AND external_id IS NOT NULL)
    );

CREATE UNIQUE INDEX IF NOT EXISTS contents_external_source_uidx
    ON content.contents (source_provider, external_id)
    WHERE source_provider IS NOT NULL AND external_id IS NOT NULL;
