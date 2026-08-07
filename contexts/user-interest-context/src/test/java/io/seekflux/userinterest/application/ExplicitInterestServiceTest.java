package io.seekflux.userinterest.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import io.seekflux.userinterest.domain.InterestProfile;
import io.seekflux.userinterest.port.out.UserInterestRepository;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ExplicitInterestServiceTest {

    @Test
    void normalizesAndDeduplicatesExplicitTopics() {
        Instant now = Instant.parse("2026-08-06T10:00:00Z");
        var repository = new StubRepository();
        var service = new ExplicitInterestService(Clock.fixed(now, ZoneOffset.UTC), repository);

        StepVerifier.create(service.save("user-1", List.of(" 露营 ", "咖啡", "露营")))
                .assertNext(profile -> {
                    assertEquals(List.of("露营", "咖啡"), profile.topics());
                    assertEquals(now, profile.updatedAt());
                })
                .verifyComplete();

        StepVerifier.create(service.resolve("user-1", List.of()))
                .assertNext(profile -> assertEquals(List.of("露营", "咖啡"), profile.topics()))
                .verifyComplete();
    }

    private static final class StubRepository implements UserInterestRepository {
        private final ConcurrentHashMap<String, InterestProfile> profiles = new ConcurrentHashMap<>();

        @Override
        public Mono<InterestProfile> findByUserId(String userId) {
            return Mono.justOrEmpty(profiles.get(userId));
        }

        @Override
        public Mono<Void> save(InterestProfile profile) {
            profiles.put(profile.userId(), profile);
            return Mono.empty();
        }
    }
}
