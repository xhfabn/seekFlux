package io.seekflux.core.feature.api;

import java.util.Map;
import java.util.UUID;

public interface RealtimeFeatureUseCase {

    FeatureRead<ShortTermInterestSnapshot> shortTermInterest(String userId);

    Map<UUID, FeatureRead<ContentHeatSnapshot>> contentHeat(Iterable<UUID> contentIds);
}
