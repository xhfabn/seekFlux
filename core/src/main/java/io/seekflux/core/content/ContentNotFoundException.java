package io.seekflux.core.content;

import io.seekflux.core.content.domain.ContentId;

public final class ContentNotFoundException extends RuntimeException {

    public ContentNotFoundException(ContentId contentId) {
        super("content not found: " + contentId);
    }
}
