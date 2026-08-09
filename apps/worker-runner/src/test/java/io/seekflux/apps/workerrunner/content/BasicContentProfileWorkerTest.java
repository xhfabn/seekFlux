package io.seekflux.apps.workerrunner.content;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.content.domain.ContentId;
import io.seekflux.content.domain.ContentStatus;
import io.seekflux.content.port.in.ContentUseCase;
import io.seekflux.content.port.in.ContentView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicContentProfileWorkerTest {

    private static final ContentId CONTENT_ID = new ContentId(
            UUID.fromString("0198b334-a7c0-7000-8000-000000000099"));

    @Test
    void skipsAStaleSubmittedEventAfterContentWasWithdrawn() throws Exception {
        ContentUseCase contentUseCase = org.mockito.Mockito.mock(ContentUseCase.class);
        when(contentUseCase.get(CONTENT_ID)).thenReturn(withdrawn());
        var worker = new BasicContentProfileWorker(contentUseCase, new ObjectMapper());

        worker.handle("""
                {
                  "event_type": "content.submitted.v1",
                  "payload": {
                    "content_id": "%s",
                    "title": "temporary fixture",
                    "description": "stale event",
                    "source_tags": ["eval"]
                  }
                }
                """.formatted(CONTENT_ID));

        verify(contentUseCase, never()).completeProfile(any());
        verify(contentUseCase, never()).publish(any());
    }

    private static ContentView withdrawn() {
        Instant now = Instant.parse("2026-08-08T12:00:00Z");
        return new ContentView(
                CONTENT_ID, "eval", "https://media.example/eval.mp4", "fixture", "",
                List.of("eval"), ContentStatus.WITHDRAWN, null, 1, now, now, null, now);
    }
}
