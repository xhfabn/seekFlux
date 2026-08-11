package io.seekflux.feature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seekflux.feature.domain.ContentHeatSnapshot;
import io.seekflux.feature.domain.FeatureReadStatus;
import io.seekflux.feature.domain.FeatureTopicScore;
import io.seekflux.feature.domain.ShortTermInterestSnapshot;
import io.seekflux.feature.port.out.OnlineFeatureRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RealtimeFeatureApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T10:00:00Z");

    @Test
    void distinguishesFreshStaleMissingAndUnavailableSnapshots() {
        assertEquals(FeatureReadStatus.FRESH, service(snapshot(NOW.minusSeconds(5)), false)
                .shortTermInterest("u1").status());
        assertEquals(FeatureReadStatus.STALE, service(snapshot(NOW.minusSeconds(31)), false)
                .shortTermInterest("u1").status());
        assertEquals(FeatureReadStatus.MISSING, service(null, false)
                .shortTermInterest("u1").status());
        assertEquals(FeatureReadStatus.UNAVAILABLE, service(null, true)
                .shortTermInterest("u1").status());
    }

    private static RealtimeFeatureApplicationService service(
            ShortTermInterestSnapshot snapshot,
            boolean fail) {
        OnlineFeatureRepository repository = new OnlineFeatureRepository() {
            @Override
            public Optional<ShortTermInterestSnapshot> findShortTermInterest(String userId) {
                if (fail) throw new IllegalStateException("redis unavailable");
                return Optional.ofNullable(snapshot);
            }

            @Override
            public Optional<ContentHeatSnapshot> findContentHeat(UUID contentId) {
                return Optional.empty();
            }
        };
        return new RealtimeFeatureApplicationService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ShortTermInterestSnapshot snapshot(Instant computedAt) {
        return new ShortTermInterestSnapshot(
                "u1", List.of(new FeatureTopicScore("露营", 3)),
                NOW.minusSeconds(1800), NOW, computedAt, RealtimeFeaturePolicy.FEATURE_VERSION);
    }
}
