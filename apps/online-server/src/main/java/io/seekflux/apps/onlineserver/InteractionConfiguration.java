package io.seekflux.apps.onlineserver;

import io.seekflux.interaction.application.InteractionApplicationService;
import io.seekflux.interaction.port.in.ReportInteractionsUseCase;
import io.seekflux.interaction.port.out.InteractionRepository;
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
