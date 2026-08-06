package io.seekflux.userinterest.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExplicitInterestServiceTest {

    @Test
    void normalizesAndDeduplicatesExplicitTopics() {
        Instant now = Instant.parse("2026-08-06T10:00:00Z");
        var service = new ExplicitInterestService(Clock.fixed(now, ZoneOffset.UTC));

        var profile = service.resolve("user-1", List.of(" 露营 ", "咖啡", "露营"));

        assertEquals(List.of("露营", "咖啡"), profile.topics());
        assertEquals(now, profile.updatedAt());
    }
}
