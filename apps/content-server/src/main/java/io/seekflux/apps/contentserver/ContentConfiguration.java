package io.seekflux.apps.contentserver;

import io.seekflux.core.content.ContentApplicationService;
import io.seekflux.core.content.api.ContentUseCase;
import io.seekflux.core.content.domain.ContentRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ContentConfiguration {

    @Bean
    Clock contentClock() {
        return Clock.systemUTC();
    }

    @Bean
    ContentUseCase contentUseCase(ContentRepository repository, Clock contentClock) {
        return new ContentApplicationService(repository, contentClock);
    }
}
