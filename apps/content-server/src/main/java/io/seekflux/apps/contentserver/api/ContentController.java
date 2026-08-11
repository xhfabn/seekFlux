package io.seekflux.apps.contentserver.api;

import io.seekflux.content.domain.ContentId;
import io.seekflux.content.domain.ContentSource;
import io.seekflux.content.domain.ContentType;
import io.seekflux.content.port.in.CompleteContentProfileCommand;
import io.seekflux.content.port.in.ContentUseCase;
import io.seekflux.content.port.in.SubmitContentCommand;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/contents")
public final class ContentController {

    private final ContentUseCase contentUseCase;

    public ContentController(ContentUseCase contentUseCase) {
        this.contentUseCase = contentUseCase;
    }

    @PostMapping
    public ResponseEntity<ContentResponse> submit(
            @Valid @RequestBody SubmitContentRequest request) {
        SubmitContentCommand command = new SubmitContentCommand(
                request.creatorId(),
                request.contentType() == null ? ContentType.VIDEO : request.contentType(),
                request.mediaUri(),
                request.assetUris(),
                request.title(),
                request.description(),
                request.body(),
                request.sourceTags(),
                new ContentSource(
                        request.sourceProvider(), request.externalId(), request.sourcePageUri(),
                        request.sourceAuthor(), request.licenseName()));
        var view = contentUseCase.submit(command);
        return ResponseEntity
                .accepted()
                .location(URI.create("/v1/contents/" + view.id()))
                .body(ContentResponse.from(view));
    }

    @GetMapping("/{contentId}")
    public ContentResponse get(@PathVariable("contentId") String contentId) {
        return ContentResponse.from(contentUseCase.get(ContentId.parse(contentId)));
    }

    @PutMapping("/{contentId}/profile")
    public ContentResponse completeProfile(
            @PathVariable("contentId") String contentId,
            @Valid @RequestBody CompleteProfileRequest request) {
        CompleteContentProfileCommand command = new CompleteContentProfileCommand(
                ContentId.parse(contentId),
                request.profileVersion(),
                request.summary(),
                request.tags(),
                request.transcript());
        return ContentResponse.from(contentUseCase.completeProfile(command));
    }

    @PostMapping("/{contentId}/publish")
    public ContentResponse publish(@PathVariable("contentId") String contentId) {
        return ContentResponse.from(contentUseCase.publish(ContentId.parse(contentId)));
    }

    @DeleteMapping("/{contentId}")
    public ContentResponse withdraw(@PathVariable("contentId") String contentId) {
        return ContentResponse.from(contentUseCase.withdraw(ContentId.parse(contentId)));
    }
}
