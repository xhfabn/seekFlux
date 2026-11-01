package io.seekflux.apps.contentserver.api;

import io.seekflux.apps.contentserver.comment.CommentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/v1/contents/{contentId}/comments")
public class CommentController {
    private final CommentService comments;

    public CommentController(CommentService comments) { this.comments = comments; }

    @GetMapping
    public CommentService.Page list(@PathVariable("contentId") UUID contentId,
            @RequestParam(value = "before", required = false) UUID before,
            @RequestParam(value = "limit", defaultValue = "20") @Min(1) @Max(50) int limit) {
        return comments.list(contentId, before, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommentService.Comment create(@PathVariable("contentId") UUID contentId,
            @Valid @RequestBody CreateComment request) {
        return comments.create(contentId, request.commentId(), request.userId(), request.body());
    }

    public record CreateComment(@NotNull UUID commentId, @NotBlank @Size(max = 128) String userId,
            @NotBlank @Size(max = 1000) String body) { }
}
