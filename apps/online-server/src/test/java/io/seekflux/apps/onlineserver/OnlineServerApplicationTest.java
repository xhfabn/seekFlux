package io.seekflux.apps.onlineserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.otlp.tracing.export.enabled=false")
class OnlineServerApplicationTest {

    @Test
    void applicationContextStarts() {
    }
}
