package io.seekflux.core.feature.api;

import io.seekflux.core.feature.domain.FeatureProjectionResult;
import io.seekflux.core.feature.domain.RealtimeFeatureEvent;

public interface RealtimeFeatureProjectionUseCase {

    FeatureProjectionResult project(RealtimeFeatureEvent event);
}
