package io.seekflux.apps.contentserver.api;

import io.seekflux.content.domain.ContentId;
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
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/contents")
public final class ContentController {

    private final ContentUseCase contentUseCase;

    public ContentController(ContentUseCase contentUseCase) {
        this.contentUseCase = contentUseCase;
    }

    @PostMapping
    public Mono<ResponseEntity<ContentResponse>> submit(
            @Valid @RequestBody SubmitContentRequest request) {
        SubmitContentCommand command = new SubmitContentCommand(
                request.creatorId(),
                request.mediaUri(),
                request.title(),
                request.description(),
                request.sourceTags());
        return contentUseCase.submit(command).map(view -> ResponseEntity
                .accepted()
                .location(URI.create("/v1/contents/" + view.id()))
                .body(ContentResponse.from(view)));
    }

    @GetMapping("/{contentId}")
    public Mono<ContentResponse> get(@PathVariable("contentId") String contentId) {
        return contentUseCase.get(ContentId.parse(contentId)).map(ContentResponse::from);
    }

    @PutMapping("/{contentId}/profile")
    public Mono<ContentResponse> completeProfile(
            @PathVariable("contentId") String contentId,
            @Valid @RequestBody CompleteProfileRequest request) {
        CompleteContentProfileCommand command = new CompleteContentProfileCommand(
                ContentId.parse(contentId),
                request.profileVersion(),
                request.summary(),
                request.tags(),
                request.transcript());
        return contentUseCase.completeProfile(command).map(ContentResponse::from);
    }

    @PostMapping("/{contentId}/publish")
    public Mono<ContentResponse> publish(@PathVariable("contentId") String contentId) {
        return contentUseCase.publish(ContentId.parse(contentId)).map(ContentResponse::from);
    }

    @DeleteMapping("/{contentId}")
    public Mono<ContentResponse> withdraw(@PathVariable("contentId") String contentId) {
        return contentUseCase.withdraw(ContentId.parse(contentId)).map(ContentResponse::from);
    }
}
