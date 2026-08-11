package io.seekflux.apps.onlineserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "management.otlp.tracing.export.enabled=false",
                "spring.flyway.enabled=false",
                "seekflux.outbox.enabled=false"
        })
class OnlineServerApplicationTest {

    @Test
    void applicationContextStarts() {
    }
}
