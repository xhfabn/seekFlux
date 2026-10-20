package io.seekflux.core.feature.domain;

import io.seekflux.core.feature.domain.FeatureProjectionResult;
import io.seekflux.core.feature.domain.RealtimeFeatureEvent;

public interface RealtimeFeatureProjectionRepository {

    FeatureProjectionResult project(RealtimeFeatureEvent event);
}
