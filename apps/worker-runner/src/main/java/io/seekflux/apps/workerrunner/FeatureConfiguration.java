package io.seekflux.apps.workerrunner;

import io.seekflux.core.feature.RealtimeFeatureProjectionService;
import io.seekflux.core.feature.api.RealtimeFeatureProjectionUseCase;
import io.seekflux.core.feature.domain.RealtimeFeatureProjectionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class FeatureConfiguration {

    @Bean
    RealtimeFeatureProjectionUseCase realtimeFeatureProjectionUseCase(
            RealtimeFeatureProjectionRepository repository) {
        return new RealtimeFeatureProjectionService(repository);
    }
}
