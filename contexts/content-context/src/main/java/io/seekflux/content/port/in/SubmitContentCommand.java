package io.seekflux.content.port.in;

import java.util.List;

public record SubmitContentCommand(
        String creatorId,
        String mediaUri,
        String title,
        String description,
        List<String> sourceTags) {
}
