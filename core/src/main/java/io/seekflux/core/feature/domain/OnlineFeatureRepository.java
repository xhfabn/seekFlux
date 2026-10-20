package io.seekflux.core.feature.domain;

import io.seekflux.core.feature.api.ContentHeatSnapshot;
import io.seekflux.core.feature.api.ShortTermInterestSnapshot;
import java.util.Optional;
import java.util.UUID;

public interface OnlineFeatureRepository {

    Optional<ShortTermInterestSnapshot> findShortTermInterest(String userId);

    Optional<ContentHeatSnapshot> findContentHeat(UUID contentId);
}
