CREATE TABLE content.comments (
    comment_id UUID PRIMARY KEY,
    content_id UUID NOT NULL REFERENCES content.contents(content_id),
    user_id VARCHAR(128) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT comments_body_not_blank CHECK (length(btrim(body)) > 0)
);

CREATE INDEX comments_content_order_idx
    ON content.comments (content_id, created_at DESC, comment_id DESC);
