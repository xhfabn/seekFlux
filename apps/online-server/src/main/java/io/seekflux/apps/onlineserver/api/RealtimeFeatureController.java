package io.seekflux.apps.onlineserver.api;

import io.seekflux.feature.domain.FeatureRead;
import io.seekflux.feature.domain.FeatureTopicScore;
import io.seekflux.feature.domain.ShortTermInterestSnapshot;
import io.seekflux.feature.port.in.RealtimeFeatureUseCase;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/features")
public class RealtimeFeatureController {

    private final RealtimeFeatureUseCase features;

    public RealtimeFeatureController(RealtimeFeatureUseCase features) {
        this.features = features;
    }

    @GetMapping("/users/{userId}/short-term-interest")
    public ShortTermInterestFeatureResponse shortTermInterest(
            @PathVariable("userId") @Size(min = 1, max = 128) String userId) {
        FeatureRead<ShortTermInterestSnapshot> read = features.shortTermInterest(userId);
        if (read.value().isEmpty()) {
            return new ShortTermInterestFeatureResponse(
                    userId, read.status().name(), List.of(), null, null, null, null);
        }
        ShortTermInterestSnapshot snapshot = read.value().orElseThrow();
        return new ShortTermInterestFeatureResponse(
                userId,
                read.status().name(),
                snapshot.topics(),
                snapshot.windowStart(),
                snapshot.windowEnd(),
                snapshot.computedAt(),
                snapshot.featureVersion());
    }

    public record ShortTermInterestFeatureResponse(
            String userId,
            String status,
            List<FeatureTopicScore> topics,
            Instant windowStart,
            Instant windowEnd,
            Instant computedAt,
            String featureVersion) {
    }
}
