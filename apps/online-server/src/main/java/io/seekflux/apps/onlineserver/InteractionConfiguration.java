package io.seekflux.apps.onlineserver;

import io.seekflux.core.feature.InteractionApplicationService;
import io.seekflux.core.feature.api.ReportInteractionsUseCase;
import io.seekflux.core.feature.domain.InteractionRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class InteractionConfiguration {

    @Bean
    ReportInteractionsUseCase reportInteractionsUseCase(
            InteractionRepository interactionRepository,
            Clock recommendationClock) {
        return new InteractionApplicationService(interactionRepository, recommendationClock);
    }
}
