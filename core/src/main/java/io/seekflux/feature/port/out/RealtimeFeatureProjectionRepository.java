package io.seekflux.feature.port.out;

import io.seekflux.feature.domain.FeatureProjectionResult;
import io.seekflux.feature.domain.RealtimeFeatureEvent;

public interface RealtimeFeatureProjectionRepository {

    FeatureProjectionResult project(RealtimeFeatureEvent event);
}
