package io.seekflux.agent.infrastructure.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventRelay;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushEvent;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushFrame;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisPushEventRelay implements PushEventRelay, AutoCloseable {

    private static final DefaultRedisScript<Long> PUBLISH_SCRIPT = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local minimum = tonumber(ARGV[3])
            if current < minimum then
                redis.call('SET', KEYS[1], minimum)
            end
            local sequence = redis.call('INCR', KEYS[1]) - 1
            local frame = cjson.decode(ARGV[2])
            frame['sequence'] = sequence
            redis.call('PUBLISH', ARGV[1], cjson.encode(frame))
            return sequence
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String channel;
    private final String sequenceKeyPrefix;
    private final RedisMessageListenerContainer container;
    private final List<Consumer<PushFrame>> listeners = new CopyOnWriteArrayList<>();

    public RedisPushEventRelay(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            Executor executor,
            String channel,
            String sequenceKeyPrefix) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.channel = requireText(channel, "push relay channel");
        this.sequenceKeyPrefix = requireText(sequenceKeyPrefix, "push sequence key prefix");
        this.container = new RedisMessageListenerContainer();
        this.container.setConnectionFactory(connectionFactory);
        this.container.setTaskExecutor(executor);
        this.container.addMessageListener((message, pattern) -> receive(
                new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(this.channel));
        this.container.afterPropertiesSet();
        this.container.start();
    }

    @Override
    public long nextSequence(String sessionId) {
        Long value = redis.opsForValue().increment(sequenceKeyPrefix + sessionId);
        if (value == null) {
            throw new IllegalStateException("Redis push sequence increment returned null");
        }
        return value - 1;
    }

    @Override
    public void broadcast(PushFrame frame) {
        try {
            WireFrame wire = new WireFrame(
                    frame.sessionId(), frame.requestId(), frame.sequence(), frame.sourceId(),
                    frame.event().getClass().getSimpleName(),
                    objectMapper.valueToTree(frame.event()), frame.publishedAt());
            redis.convertAndSend(channel, objectMapper.writeValueAsString(wire));
        } catch (Exception error) {
            throw new IllegalStateException("failed to publish Agent push relay frame", error);
        }
    }

    @Override
    public PushFrame publish(
            String sessionId,
            String requestId,
            String sourceId,
            PushEvent event,
            Instant publishedAt,
            long minimumSequence) {
        try {
            WireFrame wire = new WireFrame(
                    sessionId, requestId, 0, sourceId,
                    event.getClass().getSimpleName(),
                    objectMapper.valueToTree(event), publishedAt);
            Long sequence = redis.execute(
                    PUBLISH_SCRIPT,
                    List.of(sequenceKeyPrefix + sessionId),
                    channel,
                    objectMapper.writeValueAsString(wire),
                    Long.toString(Math.max(0, minimumSequence)));
            if (sequence == null) {
                throw new IllegalStateException("Redis push publish returned null sequence");
            }
            return new PushFrame(
                    sessionId, requestId, sequence, sourceId, event, publishedAt);
        } catch (Exception error) {
            throw new IllegalStateException("failed to atomically publish Agent push frame", error);
        }
    }

    @Override
    public AutoCloseable listen(Consumer<PushFrame> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void receive(String json) {
        try {
            WireFrame wire = objectMapper.readValue(json, WireFrame.class);
            PushEvent event = objectMapper.treeToValue(
                    wire.payload(), eventClass(wire.eventType()));
            PushFrame frame = new PushFrame(
                    wire.sessionId(), wire.requestId(), wire.sequence(), wire.sourceId(),
                    event, wire.publishedAt());
            listeners.forEach(listener -> {
                try {
                    listener.accept(frame);
                } catch (RuntimeException ignored) {
                    // A local listener cannot poison Redis relay consumption.
                }
            });
        } catch (Exception ignored) {
            // Malformed or future relay frames are isolated from current subscribers.
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends PushEvent> eventClass(String type) {
        return switch (type) {
            case "LoopStarted" -> PushEvent.LoopStarted.class;
            case "SegmentStarted" -> PushEvent.SegmentStarted.class;
            case "LlmTurnStarted" -> PushEvent.LlmTurnStarted.class;
            case "ContentDelta" -> PushEvent.ContentDelta.class;
            case "ReasoningDelta" -> PushEvent.ReasoningDelta.class;
            case "ToolCallDelta" -> PushEvent.ToolCallDelta.class;
            case "LlmTurnCompleted" -> PushEvent.LlmTurnCompleted.class;
            case "ToolStarted" -> PushEvent.ToolStarted.class;
            case "ToolCompleted" -> PushEvent.ToolCompleted.class;
            case "CheckpointSaved" -> PushEvent.CheckpointSaved.class;
            case "Control" -> PushEvent.Control.class;
            case "LoopCompleted" -> PushEvent.LoopCompleted.class;
            case "RuntimeError" -> PushEvent.RuntimeError.class;
            case "MessageQueued" -> PushEvent.MessageQueued.class;
            case "Steered" -> PushEvent.Steered.class;
            default -> throw new IllegalArgumentException("unknown push event type: " + type);
        };
    }

    @Override
    public void close() {
        container.stop();
        try {
            container.destroy();
        } catch (Exception ignored) {
            // Closing the relay is best effort during application shutdown.
        }
        listeners.clear();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record WireFrame(
            String sessionId,
            String requestId,
            long sequence,
            String sourceId,
            String eventType,
            JsonNode payload,
            Instant publishedAt) {
    }
}
