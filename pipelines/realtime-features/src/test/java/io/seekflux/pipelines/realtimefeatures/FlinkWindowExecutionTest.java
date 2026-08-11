package io.seekflux.pipelines.realtimefeatures;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seekflux.feature.domain.RealtimeFeatureEvent;
import io.seekflux.interaction.domain.InteractionType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.Test;

class FlinkWindowExecutionTest {

    @Test
    void boundedFlinkRuntimeProducesDeterministicShortTermInterest() throws Exception {
        Instant now = Instant.parse("2026-08-11T10:00:00Z");
        UUID contentId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        RealtimeFeatureEvent like = new RealtimeFeatureEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000011"),
                "flink-user", InteractionType.LIKE, contentId, List.of("露营"), now, now);
        RealtimeFeatureEvent save = new RealtimeFeatureEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000012"),
                "flink-user", InteractionType.SAVE, contentId, List.of("摄影"), now.plusSeconds(1), now.plusSeconds(1));

        StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();
        environment.setParallelism(1);
        var result = environment.fromData(like, like, save)
                .keyBy(event -> event.eventId().toString())
                .process(new EventTimeDeduplicator())
                .keyBy(RealtimeFeatureEvent::userId)
                .process(new UserInterestWindowFunction())
                .executeAndCollect(2);

        assertEquals(2, result.size());
        assertEquals(List.of("摄影", "露营"),
                result.getLast().topics().stream().map(topic -> topic.topic()).toList());
    }
}
