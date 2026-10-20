package io.seekflux.feature.port.in;

import io.seekflux.feature.domain.FeatureProjectionResult;
import io.seekflux.feature.domain.RealtimeFeatureEvent;

public interface RealtimeFeatureProjectionUseCase {

    FeatureProjectionResult project(RealtimeFeatureEvent event);
}
