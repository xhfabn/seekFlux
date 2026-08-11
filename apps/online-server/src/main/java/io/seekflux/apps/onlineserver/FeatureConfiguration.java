package io.seekflux.apps.onlineserver;

import io.seekflux.feature.application.RealtimeFeatureApplicationService;
import io.seekflux.feature.port.in.RealtimeFeatureUseCase;
import io.seekflux.feature.port.out.OnlineFeatureRepository;
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
