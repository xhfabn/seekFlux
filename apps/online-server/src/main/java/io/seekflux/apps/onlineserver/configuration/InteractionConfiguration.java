package io.seekflux.apps.onlineserver.configuration;

import io.seekflux.core.feature.InteractionApplicationService;
import io.seekflux.core.feature.api.ReportInteractionsUseCase;
import io.seekflux.core.feature.domain.InteractionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class InteractionConfiguration {

    @Bean
    ReportInteractionsUseCase reportInteractionsUseCase(
            InteractionRepository interactionRepository,
            Clock recommendationClock) {
        return new InteractionApplicationService(interactionRepository, recommendationClock);
    }
}
