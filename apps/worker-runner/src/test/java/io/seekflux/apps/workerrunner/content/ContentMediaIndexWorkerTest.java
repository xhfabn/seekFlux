package io.seekflux.apps.workerrunner.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.search.port.out.MediaEmbeddingBatch;
import io.seekflux.search.port.out.MediaEmbeddingSegment;
import io.seekflux.search.port.out.MediaSegmentDocument;
import io.seekflux.search.port.out.MediaSegmentIndex;
import io.seekflux.search.port.out.MediaUnderstandingBatch;
import io.seekflux.search.port.out.MediaUnderstandingEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContentMediaIndexWorkerTest {

    @Test
    void writesVersionedMetadataAndModelEvidenceIntoEachMediaSegment() throws Exception {
        List<MediaSegmentDocument> indexed = new ArrayList<>();
        MediaSegmentIndex index = new MediaSegmentIndex() {
            @Override public void upsert(MediaSegmentDocument document) { indexed.add(document); }
            @Override public void deleteByContentId(String contentId) { }
        };
        var worker = new ContentMediaIndexWorker((modality, input, maxSegments) ->
                new MediaUnderstandingBatch(
                        new MediaEmbeddingBatch("siglip-test", 2,
                                List.of(new MediaEmbeddingSegment(0, 0, 5000, input, List.of(1.0, 0.0)))),
                        List.of(
                                new MediaUnderstandingEvidence("OCR", "屏幕上的咖啡配方", 0.92,
                                        0, 5000, "rapidocr-test"),
                                new MediaUnderstandingEvidence("ASR", "先磨咖啡豆", 0.88,
                                        0, 5000, "whisper-test")),
                        Map.of("VISUAL", "AVAILABLE", "OCR", "AVAILABLE", "ASR", "AVAILABLE")),
                index, new ObjectMapper(), 12);

        worker.indexPublished("""
                {"event_time":"2026-08-18T00:00:00Z","payload":{
                  "content_id":"7cb45a68-12a2-4c89-af51-53e4d7089d94","content_type":"VIDEO",
                  "media_uri":"https://media/video.mp4","asset_uris":["https://media/video.mp4"],
                  "title":"手冲咖啡","description":"新手教程","body":"水温与研磨度",
                  "summary":"咖啡入门","tags":["咖啡","教程"],"transcript":""
                }}
                """);

        assertEquals(1, indexed.size());
        MediaSegmentDocument document = indexed.getFirst();
        assertTrue(document.understandingText().contains("手冲咖啡"));
        assertTrue(document.understandingText().contains("屏幕上的咖啡配方"));
        assertTrue(document.understandingText().contains("先磨咖啡豆"));
        assertEquals(List.of("METADATA", "OCR", "ASR"),
                document.evidence().stream().map(MediaUnderstandingEvidence::channel).toList());
        assertEquals("AVAILABLE", document.channelStatuses().get("METADATA"));
    }
}
