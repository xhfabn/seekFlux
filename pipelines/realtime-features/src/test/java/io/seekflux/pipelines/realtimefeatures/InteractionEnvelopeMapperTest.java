package io.seekflux.pipelines.realtimefeatures;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seekflux.interaction.domain.InteractionType;
import org.junit.jupiter.api.Test;

class InteractionEnvelopeMapperTest {

    @Test
    void mapsVersionedInteractionEnvelopeWithContentTags() throws Exception {
        String json = """
                {
                  "event_id":"00000000-0000-0000-0000-000000000001",
                  "ingested_at":"2026-08-11T10:00:01Z",
                  "payload":{
                    "user_id":"u1",
                    "event_type":"LIKE",
                    "content_id":"00000000-0000-0000-0000-000000000002",
                    "content_tags":["露营","旅行"],
                    "event_time":"2026-08-11T10:00:00Z"
                  }
                }
                """;

        var event = new InteractionEnvelopeMapper().map(json);

        assertEquals("u1", event.userId());
        assertEquals(InteractionType.LIKE, event.eventType());
        assertEquals(java.util.List.of("露营", "旅行"), event.contentTags());
    }
}
