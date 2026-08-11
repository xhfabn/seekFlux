package io.seekflux.feature.application;

import io.seekflux.feature.domain.FeatureProjectionResult;
import io.seekflux.feature.domain.RealtimeFeatureEvent;
import io.seekflux.feature.port.in.RealtimeFeatureProjectionUseCase;
import io.seekflux.feature.port.out.RealtimeFeatureProjectionRepository;
import java.util.Objects;

public final class RealtimeFeatureProjectionService implements RealtimeFeatureProjectionUseCase {

    private final RealtimeFeatureProjectionRepository repository;

    public RealtimeFeatureProjectionService(RealtimeFeatureProjectionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public FeatureProjectionResult project(RealtimeFeatureEvent event) {
        return repository.project(Objects.requireNonNull(event, "feature event must not be null"));
    }
}
