package io.seekflux.userinterest.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import io.seekflux.userinterest.domain.InterestProfile;
import io.seekflux.userinterest.port.out.UserInterestRepository;

class ExplicitInterestServiceTest {

    @Test
    void normalizesAndDeduplicatesExplicitTopics() {
        Instant now = Instant.parse("2026-08-06T10:00:00Z");
        var repository = new StubRepository();
        var service = new ExplicitInterestService(Clock.fixed(now, ZoneOffset.UTC), repository);

        var saved = service.save("user-1", List.of(" 露营 ", "咖啡", "露营"));
        assertEquals(List.of("露营", "咖啡"), saved.topics());
        assertEquals(now, saved.updatedAt());

        var resolved = service.resolve("user-1", List.of());
        assertEquals(List.of("露营", "咖啡"), resolved.topics());
    }

    private static final class StubRepository implements UserInterestRepository {
        private final ConcurrentHashMap<String, InterestProfile> profiles = new ConcurrentHashMap<>();

        @Override
        public Optional<InterestProfile> findByUserId(String userId) {
            return Optional.ofNullable(profiles.get(userId));
        }

        @Override
        public void save(InterestProfile profile) {
            profiles.put(profile.userId(), profile);
        }
    }
}
