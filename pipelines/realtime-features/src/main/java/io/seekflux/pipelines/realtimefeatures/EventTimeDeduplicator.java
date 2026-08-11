package io.seekflux.pipelines.realtimefeatures;

import io.seekflux.feature.application.RealtimeFeaturePolicy;
import io.seekflux.feature.domain.RealtimeFeatureEvent;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

public final class EventTimeDeduplicator
        extends KeyedProcessFunction<String, RealtimeFeatureEvent, RealtimeFeatureEvent> {

    public static final OutputTag<RealtimeFeatureEvent> LATE_EVENTS =
            new OutputTag<>("feature-interaction-late") { };

    private transient ValueState<Boolean> seen;

    @Override
    public void open(OpenContext openContext) {
        ValueStateDescriptor<Boolean> descriptor = new ValueStateDescriptor<>("seen-event-id", Boolean.class);
        descriptor.enableTimeToLive(StateTtlConfig.newBuilder(java.time.Duration.ofHours(2)).build());
        seen = getRuntimeContext().getState(descriptor);
    }

    @Override
    public void processElement(
            RealtimeFeatureEvent event,
            Context context,
            Collector<RealtimeFeatureEvent> output) throws Exception {
        if (seen.value() != null) {
            return;
        }
        seen.update(Boolean.TRUE);
        long watermark = context.timerService().currentWatermark();
        long lateBoundary = watermark == Long.MIN_VALUE
                ? Long.MIN_VALUE
                : watermark - RealtimeFeaturePolicy.ALLOWED_LATENESS.toMillis();
        if (event.eventTime().toEpochMilli() < lateBoundary) {
            context.output(LATE_EVENTS, event);
            return;
        }
        output.collect(event);
    }
}
