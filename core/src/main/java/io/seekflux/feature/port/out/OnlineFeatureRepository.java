package io.seekflux.feature.port.out;

import io.seekflux.feature.domain.ContentHeatSnapshot;
import io.seekflux.feature.domain.ShortTermInterestSnapshot;
import java.util.Optional;
import java.util.UUID;

public interface OnlineFeatureRepository {

    Optional<ShortTermInterestSnapshot> findShortTermInterest(String userId);

    Optional<ContentHeatSnapshot> findContentHeat(UUID contentId);
}
