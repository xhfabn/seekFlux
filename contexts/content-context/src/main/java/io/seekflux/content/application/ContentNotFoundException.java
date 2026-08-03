package io.seekflux.content.application;

import io.seekflux.content.domain.ContentId;

public final class ContentNotFoundException extends RuntimeException {

    public ContentNotFoundException(ContentId contentId) {
        super("content not found: " + contentId);
    }
}
