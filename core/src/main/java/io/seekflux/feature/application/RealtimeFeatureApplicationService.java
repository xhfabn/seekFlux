package io.seekflux.feature.application;

import io.seekflux.feature.domain.ContentHeatSnapshot;
import io.seekflux.feature.domain.FeatureRead;
import io.seekflux.feature.domain.FeatureReadStatus;
import io.seekflux.feature.domain.ShortTermInterestSnapshot;
import io.seekflux.feature.port.in.RealtimeFeatureUseCase;
import io.seekflux.feature.port.out.OnlineFeatureRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public final class RealtimeFeatureApplicationService implements RealtimeFeatureUseCase {

    private final OnlineFeatureRepository repository;
    private final Clock clock;

    public RealtimeFeatureApplicationService(OnlineFeatureRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public FeatureRead<ShortTermInterestSnapshot> shortTermInterest(String userId) {
        String normalized = userId == null ? "" : userId.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        return read(() -> repository.findShortTermInterest(normalized), ShortTermInterestSnapshot::computedAt);
    }

    @Override
    public Map<UUID, FeatureRead<ContentHeatSnapshot>> contentHeat(Iterable<UUID> contentIds) {
        LinkedHashMap<UUID, FeatureRead<ContentHeatSnapshot>> result = new LinkedHashMap<>();
        for (UUID contentId : contentIds) {
            if (contentId != null) {
                result.put(contentId, read(
                        () -> repository.findContentHeat(contentId), ContentHeatSnapshot::computedAt));
            }
        }
        return Map.copyOf(result);
    }

    private <T> FeatureRead<T> read(
            java.util.function.Supplier<java.util.Optional<T>> operation,
            Function<T, Instant> computedAt) {
        try {
            java.util.Optional<T> value = operation.get();
            if (value.isEmpty()) {
                return FeatureRead.empty(FeatureReadStatus.MISSING);
            }
            Instant cutoff = clock.instant().minus(RealtimeFeaturePolicy.MAX_FEATURE_AGE);
            if (computedAt.apply(value.get()).isBefore(cutoff)) {
                return FeatureRead.empty(FeatureReadStatus.STALE);
            }
            return FeatureRead.fresh(value.get());
        } catch (RuntimeException unavailable) {
            return FeatureRead.empty(FeatureReadStatus.UNAVAILABLE);
        }
    }
}
