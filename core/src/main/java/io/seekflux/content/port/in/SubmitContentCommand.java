package io.seekflux.content.port.in;

import io.seekflux.content.domain.ContentSource;
import io.seekflux.content.domain.ContentType;
import java.util.List;

public record SubmitContentCommand(
        String creatorId,
        ContentType contentType,
        String mediaUri,
        List<String> assetUris,
        String title,
        String description,
        String body,
        List<String> sourceTags,
        ContentSource source) {

    public SubmitContentCommand(
            String creatorId,
            String mediaUri,
            String title,
            String description,
            List<String> sourceTags) {
        this(creatorId, ContentType.VIDEO, mediaUri, List.of(mediaUri), title, description,
                "", sourceTags, ContentSource.manual());
    }
}
