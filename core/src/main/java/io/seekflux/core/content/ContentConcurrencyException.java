package io.seekflux.core.content;

import io.seekflux.core.content.domain.ContentId;

public final class ContentConcurrencyException extends RuntimeException {

    public ContentConcurrencyException(ContentId contentId) {
        super("content changed concurrently: " + contentId);
    }
}
