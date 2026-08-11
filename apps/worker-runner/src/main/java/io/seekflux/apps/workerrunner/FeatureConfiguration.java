package io.seekflux.apps.workerrunner;

import io.seekflux.feature.application.RealtimeFeatureProjectionService;
import io.seekflux.feature.port.in.RealtimeFeatureProjectionUseCase;
import io.seekflux.feature.port.out.RealtimeFeatureProjectionRepository;
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
