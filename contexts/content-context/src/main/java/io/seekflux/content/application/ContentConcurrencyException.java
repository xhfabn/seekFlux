package io.seekflux.content.application;

import io.seekflux.content.domain.ContentId;

public final class ContentConcurrencyException extends RuntimeException {

    public ContentConcurrencyException(ContentId contentId) {
        super("content changed concurrently: " + contentId);
    }
}
