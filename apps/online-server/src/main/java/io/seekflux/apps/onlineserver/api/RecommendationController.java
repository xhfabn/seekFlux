package io.seekflux.apps.onlineserver.api;

import io.seekflux.recommendation.port.in.FeedRequest;
import io.seekflux.recommendation.port.in.RecommendationPage;
import io.seekflux.recommendation.port.in.RecommendationUseCase;
import io.seekflux.recommendation.port.in.SimilarContentRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1")
public class RecommendationController {

    private final RecommendationUseCase recommendation;

    public RecommendationController(RecommendationUseCase recommendation) {
        this.recommendation = recommendation;
    }

    @GetMapping("/feed")
    public RecommendationPage feed(
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") @Size(max = 128) String userId,
            @RequestParam(value = "cursor", required = false) @Size(max = 2_048) String cursor,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(50) int pageSize,
            @RequestParam(value = "interests", required = false) @Size(max = 1_300) String interests,
            @RequestParam(name = "seed_content_id", required = false) @Size(max = 128) String seedContentId) {
        return recommendation.feed(new FeedRequest(
                userId, parseInterests(interests), seedContentId, cursor, pageSize));
    }

    @GetMapping("/contents/{contentId}/similar")
    public RecommendationPage similar(
            @PathVariable("contentId") @Size(max = 128) String contentId,
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") @Size(max = 128) String userId,
            @RequestParam(value = "cursor", required = false) @Size(max = 2_048) String cursor,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(50) int pageSize,
            @RequestParam(value = "interests", required = false) @Size(max = 1_300) String interests) {
        return recommendation.similar(new SimilarContentRequest(
                contentId, userId, parseInterests(interests), cursor, pageSize));
    }

    private static List<String> parseInterests(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("[,，]"))
                .map(String::trim)
                .filter(topic -> !topic.isEmpty())
                .toList();
    }
}
