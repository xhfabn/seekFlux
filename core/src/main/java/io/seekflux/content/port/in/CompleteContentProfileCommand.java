package io.seekflux.content.port.in;

import io.seekflux.content.domain.ContentId;
import java.util.List;

public record CompleteContentProfileCommand(
        ContentId contentId,
        int profileVersion,
        String summary,
        List<String> tags,
        String transcript) {
}
