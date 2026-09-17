package io.seekflux.apps.agentserver.interfaces.rest;

import io.seekflux.agent.domain.ConstraintPatch;
import io.seekflux.agent.port.in.AgentRequestedMode;
import io.seekflux.agent.port.in.AgentSearchCommand;
import io.seekflux.agent.port.in.AgentSearchUseCase;
import io.seekflux.platform.agentruntime.application.api.Router;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventStream;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/agent")
public class AgentSearchController {

    private final AgentSearchUseCase agentSearch;
    private final Router router;
    private final PushEventStream pushEvents;
    private final ExecutorService sseExecutor;
    private final long sseTimeoutMillis;

    public AgentSearchController(AgentSearchUseCase agentSearch, Router router) {
        this(agentSearch, router, null, null, 30_000);
    }

    @Autowired
    public AgentSearchController(
            AgentSearchUseCase agentSearch,
            Router router,
            PushEventStream pushEvents,
            @Qualifier("agentSseExecutor") ExecutorService sseExecutor,
            @Value("${seekflux.agent.push.sse-timeout-ms:30000}") long sseTimeoutMillis) {
        this.agentSearch = agentSearch;
        this.router = router;
        this.pushEvents = pushEvents;
        this.sseExecutor = sseExecutor;
        this.sseTimeoutMillis = sseTimeoutMillis;
    }

    @PostMapping("/search")
    public AgentSearchResponse search(@Valid @RequestBody AgentSearchRequest request) {
        return AgentSearchResponse.from(agentSearch.search(command(request)));
    }

    @PostMapping(path = "/search:stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @Valid @RequestBody AgentSearchRequest request,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        if (pushEvents == null || sseExecutor == null) {
            throw new IllegalStateException("Agent streaming is not configured");
        }
        AgentSearchCommand command = command(request);
        long afterSequence = parseLastEventId(lastEventId);
        boolean reconnect = afterSequence >= 0;
        PushEventStream.Subscription subscription =
                pushEvents.subscribe(command.sessionId(), afterSequence);
        SseEmitter emitter = new SseEmitter(sseTimeoutMillis);
        AtomicBoolean executionDone = new AtomicBoolean();
        AtomicBoolean disconnected = new AtomicBoolean();
        AtomicReference<AgentSearchResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable close = () -> {
            disconnected.set(true);
            subscription.close();
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(ignored -> close.run());
        Future<?> executionFuture = null;
        try {
            if (!reconnect) {
                var publisher = pushEvents.publisher(command.sessionId(), command.requestId());
                executionFuture = sseExecutor.submit(() -> {
                    try {
                        response.set(AgentSearchResponse.from(
                                agentSearch.search(command, publisher)));
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        executionDone.set(true);
                    }
                });
            }
            sseExecutor.submit(() -> pump(
                    emitter, subscription, executionDone, disconnected, response, failure,
                    reconnect));
        } catch (RejectedExecutionException saturated) {
            if (executionFuture != null) {
                executionFuture.cancel(true);
                try {
                    router.cancel(command.sessionId(), false);
                } catch (RuntimeException ignored) {
                    // Saturation cleanup is best effort; the response still closes deterministically.
                }
            }
            close.run();
            emitter.completeWithError(new IllegalStateException("AGENT_STREAM_SATURATED", saturated));
        }
        return emitter;
    }

    private void pump(
            SseEmitter emitter,
            PushEventStream.Subscription subscription,
            AtomicBoolean executionDone,
            AtomicBoolean disconnected,
            AtomicReference<AgentSearchResponse> response,
            AtomicReference<Throwable> failure,
            boolean reconnect) {
        try {
            if (subscription.replayGap()) {
                emitter.send(SseEmitter.event().name("control")
                        .data(Map.of("code", "REPLAY_GAP")));
            }
            if (reconnect && subscription.terminalObserved()) {
                emitter.complete();
                return;
            }
            while (!disconnected.get()) {
                var frame = subscription.poll(Duration.ofMillis(100));
                if (frame != null) {
                    emitter.send(SseEmitter.event()
                            .id(Long.toString(frame.sequence()))
                            .name(frame.event().getClass().getSimpleName())
                            .data(frame));
                    if (reconnect && frame.event() instanceof PushEvent.LoopCompleted) {
                        emitter.complete();
                        return;
                    }
                    continue;
                }
                if (subscription.overflowed()) {
                    throw new IllegalStateException("AGENT_STREAM_BACKPRESSURE_OVERFLOW");
                }
                if (executionDone.get()) {
                    Throwable error = failure.get();
                    if (error != null) {
                        throw error;
                    }
                    AgentSearchResponse finalResponse = response.get();
                    if (finalResponse != null) {
                        emitter.send(SseEmitter.event().name("result").data(finalResponse));
                    }
                    emitter.complete();
                    return;
                }
            }
        } catch (Throwable error) {
            if (!disconnected.get()) {
                emitter.completeWithError(error);
            }
        } finally {
            subscription.close();
        }
    }

    private static long parseLastEventId(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < 0) {
                throw new NumberFormatException("negative event id");
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Last-Event-ID must be a non-negative integer");
        }
    }

    private static AgentSearchCommand command(AgentSearchRequest request) {
        String requestId = request.requestId() == null || request.requestId().isBlank()
                ? UUID.randomUUID().toString()
                : request.requestId().trim();
        String agentId = request.agentId() == null || request.agentId().isBlank()
                ? "search-assistant"
                : request.agentId().trim();
        boolean allowClarification = request.options() == null
                || request.options().allowClarification() == null
                || request.options().allowClarification();
        return new AgentSearchCommand(
                requestId,
                request.sessionId(),
                request.turnId(),
                agentId,
                request.query(),
                request.page() == null ? 0 : request.page(),
                request.size() == null ? 12 : request.size(),
                request.requiredTags() == null ? List.of() : request.requiredTags(),
                allowClarification,
                request.mode() == null ? AgentRequestedMode.AUTO : request.mode(),
                constraintPatch(request.constraintPatch()),
                request.ingressMode());
    }

    private static ConstraintPatch constraintPatch(AgentSearchRequest.ConstraintPatchRequest patch) {
        if (patch == null) {
            return null;
        }
        return new ConstraintPatch(
                patch.baseVersion(),
                patch.replacementQuery(),
                patch.page(),
                patch.size(),
                patch.addRequiredTags() == null ? List.of() : patch.addRequiredTags(),
                patch.removeRequiredTags() == null ? List.of() : patch.removeRequiredTags());
    }

    @PostMapping("/sessions/{sessionId}:cancel")
    public Map<String, Object> cancel(
            @PathVariable("sessionId") @Size(min = 1, max = 128) String sessionId) {
        boolean cancelled = router.cancel(sessionId, false);
        return Map.of("sessionId", sessionId, "cancelled", cancelled);
    }
}
