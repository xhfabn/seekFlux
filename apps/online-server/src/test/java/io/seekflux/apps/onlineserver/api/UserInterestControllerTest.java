package io.seekflux.apps.onlineserver.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seekflux.userinterest.domain.InterestProfile;
import io.seekflux.userinterest.port.in.UserInterestUseCase;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class UserInterestControllerTest {

    @Test
    void savesTheProfileUsedByRecommendation() {
        AtomicReference<InterestProfile> stored = new AtomicReference<>();
        UserInterestUseCase useCase = new UserInterestUseCase() {
            @Override
            public Mono<InterestProfile> resolve(String userId, List<String> explicitTopics) {
                return Mono.just(stored.get());
            }

            @Override
            public Mono<InterestProfile> save(String userId, List<String> topics) {
                InterestProfile profile = new InterestProfile(
                        userId, topics, Instant.parse("2026-08-08T10:00:00Z"));
                stored.set(profile);
                return Mono.just(profile);
            }
        };
        WebTestClient client = WebTestClient.bindToController(new UserInterestController(useCase)).build();

        client.put().uri("/v1/users/demo-user/interest-profile")
                .bodyValue("{\"topics\":[\"露营\",\"摄影\"]}")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo("demo-user")
                .jsonPath("$.topics[0]").isEqualTo("露营")
                .jsonPath("$.topics[1]").isEqualTo("摄影");

        assertEquals(List.of("露营", "摄影"), stored.get().topics());
    }
}
