package io.seekflux.feature.port.in;

import io.seekflux.feature.domain.ContentHeatSnapshot;
import io.seekflux.feature.domain.FeatureRead;
import io.seekflux.feature.domain.ShortTermInterestSnapshot;
import java.util.Map;
import java.util.UUID;

public interface RealtimeFeatureUseCase {

    FeatureRead<ShortTermInterestSnapshot> shortTermInterest(String userId);

    Map<UUID, FeatureRead<ContentHeatSnapshot>> contentHeat(Iterable<UUID> contentIds);
}
