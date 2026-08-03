package io.seekflux.apps.contentserver;

import io.seekflux.content.application.ContentApplicationService;
import io.seekflux.content.port.in.ContentUseCase;
import io.seekflux.content.port.out.ContentRepository;
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
