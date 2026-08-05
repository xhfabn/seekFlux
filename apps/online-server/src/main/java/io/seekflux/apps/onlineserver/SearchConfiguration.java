package io.seekflux.apps.onlineserver;

import io.seekflux.search.application.SearchApplicationService;
import io.seekflux.search.port.in.SearchUseCase;
import io.seekflux.search.port.out.SearchRetriever;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class SearchConfiguration {

    @Bean
    SearchUseCase searchUseCase(SearchRetriever retriever) {
        return new SearchApplicationService(retriever);
    }
}
