package io.seekflux.pipelines.realtimefeatures;

import io.seekflux.feature.application.RealtimeFeaturePolicy;
import io.seekflux.feature.domain.ContentHeatSnapshot;
import io.seekflux.feature.domain.RealtimeFeatureEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

public final class ContentHeatWindowFunction
        extends KeyedProcessFunction<String, RealtimeFeatureEvent, ContentHeatSnapshot> {

    private transient ListState<RealtimeFeatureEvent> events;
    private transient ValueState<Long> maxEventTime;
    private transient RealtimeFeaturePolicy policy;

    @Override
    public void open(OpenContext openContext) {
        events = getRuntimeContext().getListState(
                new ListStateDescriptor<>("content-window-events", RealtimeFeatureEvent.class));
        maxEventTime = getRuntimeContext().getState(
                new ValueStateDescriptor<>("content-max-event-time", Long.class));
        policy = new RealtimeFeaturePolicy();
    }

    @Override
    public void processElement(
            RealtimeFeatureEvent event,
            Context context,
            Collector<ContentHeatSnapshot> output) throws Exception {
        long previous = maxEventTime.value() == null ? Long.MIN_VALUE : maxEventTime.value();
        long anchorMillis = Math.max(previous, event.eventTime().toEpochMilli());
        maxEventTime.update(anchorMillis);
        Instant anchor = Instant.ofEpochMilli(anchorMillis);
        Instant cutoff = anchor.minus(RealtimeFeaturePolicy.CONTENT_HEAT_WINDOW);
        List<RealtimeFeatureEvent> retained = new ArrayList<>();
        for (RealtimeFeatureEvent stored : events.get()) {
            if (!stored.eventTime().isBefore(cutoff)) {
                retained.add(stored);
            }
        }
        retained.add(event);
        events.update(retained);
        output.collect(policy.contentHeat(
                event.contentId(), retained, anchor, Instant.ofEpochMilli(context.timerService().currentProcessingTime())));
    }
}
