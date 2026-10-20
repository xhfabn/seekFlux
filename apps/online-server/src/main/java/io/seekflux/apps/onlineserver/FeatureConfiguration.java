package io.seekflux.apps.onlineserver;

import io.seekflux.core.feature.RealtimeFeatureApplicationService;
import io.seekflux.core.feature.api.RealtimeFeatureUseCase;
import io.seekflux.core.feature.domain.OnlineFeatureRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class FeatureConfiguration {

    @Bean
    RealtimeFeatureUseCase realtimeFeatureUseCase(
            OnlineFeatureRepository repository,
            Clock recommendationClock) {
        return new RealtimeFeatureApplicationService(repository, recommendationClock);
    }
}
