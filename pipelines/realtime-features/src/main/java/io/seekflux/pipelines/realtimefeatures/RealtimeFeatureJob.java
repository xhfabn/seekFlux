package io.seekflux.pipelines.realtimefeatures;

import io.seekflux.feature.application.FeatureTopics;
import io.seekflux.feature.application.RealtimeFeaturePolicy;
import io.seekflux.feature.domain.ContentHeatSnapshot;
import io.seekflux.feature.domain.RealtimeFeatureEvent;
import io.seekflux.feature.domain.ShortTermInterestSnapshot;
import io.seekflux.interaction.application.InteractionTopics;
import java.time.Duration;
import java.util.List;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

public final class RealtimeFeatureJob {

    private RealtimeFeatureJob() {
    }

    public static void main(String[] args) throws Exception {
        String bootstrapServers = environment("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();
        environment.enableCheckpointing(10_000, CheckpointingMode.EXACTLY_ONCE);
        environment.getCheckpointConfig().setMinPauseBetweenCheckpoints(5_000);

        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers(bootstrapServers)
                .setTopics(interactionTopics())
                .setGroupId(environment("FEATURE_FLINK_GROUP_ID", "seekflux-realtime-features-flink-v1"))
                .setStartingOffsets(OffsetsInitializer.committedOffsets(
                        org.apache.kafka.clients.consumer.OffsetResetStrategy.EARLIEST))
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        DataStream<RealtimeFeatureEvent> parsed = environment
                .fromSource(source, WatermarkStrategy.noWatermarks(), "interaction-kafka-source")
                .map(new InteractionEnvelopeMapper())
                .assignTimestampsAndWatermarks(WatermarkStrategy
                        .<RealtimeFeatureEvent>forBoundedOutOfOrderness(RealtimeFeaturePolicy.OUT_OF_ORDER_BOUND)
                        .withTimestampAssigner((event, previous) -> event.eventTime().toEpochMilli())
                        .withIdleness(Duration.ofSeconds(30)));
        SingleOutputStreamOperator<RealtimeFeatureEvent> accepted = parsed
                .keyBy(event -> event.eventId().toString())
                .process(new EventTimeDeduplicator());

        DataStream<ShortTermInterestSnapshot> interests = accepted
                .keyBy(RealtimeFeatureEvent::userId)
                .process(new UserInterestWindowFunction());
        DataStream<ContentHeatSnapshot> heat = accepted
                .keyBy(event -> event.contentId().toString())
                .process(new ContentHeatWindowFunction());

        KafkaSink<String> snapshotSink = sink(bootstrapServers, FeatureTopics.SNAPSHOT_UPDATED);
        interests.map(new FeatureEnvelopeMapper.Interest())
                .union(heat.map(new FeatureEnvelopeMapper.Heat()))
                .sinkTo(snapshotSink)
                .name("online-feature-snapshot-sink");
        accepted.getSideOutput(EventTimeDeduplicator.LATE_EVENTS)
                .map(new FeatureEnvelopeMapper.Late())
                .sinkTo(sink(bootstrapServers, FeatureTopics.LATE_EVENT))
                .name("late-interaction-compensation-sink");

        environment.execute("seekflux-realtime-features-v1");
    }

    private static KafkaSink<String> sink(String bootstrapServers, String topic) {
        return KafkaSink.<String>builder()
                .setBootstrapServers(bootstrapServers)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(topic)
                        .setValueSerializationSchema(new SimpleStringSchema())
                        .build())
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .build();
    }

    private static List<String> interactionTopics() {
        return List.of(
                InteractionTopics.EXPOSURE,
                InteractionTopics.CLICK,
                InteractionTopics.PLAY_START,
                InteractionTopics.LIKE,
                InteractionTopics.SAVE,
                InteractionTopics.PLAY_COMPLETE,
                InteractionTopics.NOT_INTERESTED);
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
