package io.seekflux.platform.agentruntime.infrastructure.event;

import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventRelay;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventStream;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushEvent;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushFrame;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class DefaultPushEventStream implements PushEventStream, AutoCloseable {

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final int historyCapacity;
    private final int subscriberCapacity;
    private final int maxSessions;
    private final String sourceId;
    private final PushEventRelay relay;
    private final Clock clock;
    private final AutoCloseable relayListener;

    public DefaultPushEventStream(int historyCapacity, int subscriberCapacity, int maxSessions) {
        this(historyCapacity, subscriberCapacity, maxSessions,
                UUID.randomUUID().toString(), PushEventRelay.LOCAL_ONLY, Clock.systemUTC());
    }

    public DefaultPushEventStream(
            int historyCapacity,
            int subscriberCapacity,
            int maxSessions,
            String sourceId,
            PushEventRelay relay,
            Clock clock) {
        if (historyCapacity < 1 || subscriberCapacity < 1 || maxSessions < 1) {
            throw new IllegalArgumentException("push capacities must be positive");
        }
        this.historyCapacity = historyCapacity;
        this.subscriberCapacity = subscriberCapacity;
        this.maxSessions = maxSessions;
        this.sourceId = sourceId == null || sourceId.isBlank()
                ? UUID.randomUUID().toString() : sourceId;
        this.relay = relay == null ? PushEventRelay.LOCAL_ONLY : relay;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.relayListener = this.relay.listen(this::acceptRemote);
    }

    @Override
    public PushEventPublisher publisher(String sessionId, String requestId) {
        String requiredSession = requireText(sessionId, "session id");
        String normalizedRequest = requestId == null ? "" : requestId;
        return event -> publish(requiredSession, normalizedRequest, event);
    }

    @Override
    public Subscription subscribe(String sessionId, long afterSequence) {
        if (afterSequence < -1) {
            throw new IllegalArgumentException("after sequence must be at least -1");
        }
        SessionState state = state(requireText(sessionId, "session id"));
        Subscriber subscriber = new Subscriber(state, subscriberCapacity);
        state.subscribe(subscriber, afterSequence);
        return subscriber;
    }

    private long publish(String sessionId, String requestId, PushEvent event) {
        SessionState state = state(sessionId);
        PushFrame frame;
        try {
            frame = relay.publish(
                    sessionId, requestId, sourceId, event, clock.instant(),
                    state.minimumNextSequence());
        } catch (RuntimeException unavailable) {
            frame = null;
        }
        if (frame == null || frame.sequence() < 0) {
            long sequence = state.nextLocalSequence();
            frame = new PushFrame(
                    sessionId, requestId, sequence, sourceId, event, clock.instant());
        } else {
            state.observeSequence(frame.sequence());
        }
        state.accept(frame);
        return frame.sequence();
    }

    private void acceptRemote(PushFrame frame) {
        if (frame == null || sourceId.equals(frame.sourceId())) {
            return;
        }
        SessionState state = state(frame.sessionId());
        state.observeSequence(frame.sequence());
        state.accept(frame);
    }

    private synchronized SessionState state(String sessionId) {
        SessionState existing = sessions.get(sessionId);
        if (existing != null) {
            return existing;
        }
        while (sessions.size() >= maxSessions) {
            String victim = sessions.entrySet().stream()
                    .filter(entry -> entry.getValue().subscriberCount() == 0)
                    .min(Comparator.comparingLong(entry -> entry.getValue().lastAccessNanos()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (victim == null) {
                throw new IllegalStateException("AGENT_PUSH_SESSION_CAPACITY_EXCEEDED");
            }
            SessionState removed = sessions.remove(victim);
            if (removed != null) {
                removed.close();
            }
        }
        SessionState created = new SessionState(historyCapacity);
        sessions.put(sessionId, created);
        return created;
    }

    @Override
    public void close() {
        try {
            relayListener.close();
        } catch (Exception ignored) {
        }
        sessions.values().forEach(SessionState::close);
        sessions.clear();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static final class SessionState {
        private final AtomicLong sequence = new AtomicLong(-1);
        private final Deque<PushFrame> history = new ArrayDeque<>();
        private final List<Subscriber> subscribers = new ArrayList<>();
        private final int historyCapacity;
        private volatile long lastAccessNanos = System.nanoTime();

        private SessionState(int historyCapacity) {
            this.historyCapacity = historyCapacity;
        }

        private long nextLocalSequence() {
            lastAccessNanos = System.nanoTime();
            return sequence.incrementAndGet();
        }

        private long minimumNextSequence() {
            return sequence.get() + 1;
        }

        private void observeSequence(long observed) {
            sequence.accumulateAndGet(observed, Math::max);
        }

        private synchronized void accept(PushFrame frame) {
            lastAccessNanos = System.nanoTime();
            if (!history.isEmpty() && history.getLast().sequence() >= frame.sequence()) {
                if (history.stream().anyMatch(existing ->
                        existing.sequence() == frame.sequence()
                                && existing.sourceId().equals(frame.sourceId()))) {
                    return;
                }
            }
            history.addLast(frame);
            while (history.size() > historyCapacity) {
                history.removeFirst();
            }
            List.copyOf(subscribers).forEach(subscriber -> subscriber.offer(frame));
        }

        private synchronized void subscribe(Subscriber subscriber, long afterSequence) {
            lastAccessNanos = System.nanoTime();
            if (!history.isEmpty() && afterSequence >= 0
                    && history.getFirst().sequence() > afterSequence + 1) {
                subscriber.replayGap = true;
            }
            subscriber.terminalObserved = history.stream().anyMatch(frame ->
                    frame.sequence() <= afterSequence
                            && frame.event() instanceof PushEvent.LoopCompleted);
            history.stream()
                    .filter(frame -> frame.sequence() > afterSequence)
                    .sorted(Comparator.comparingLong(PushFrame::sequence))
                    .forEach(subscriber::offer);
            if (!subscriber.overflowed()) {
                subscribers.add(subscriber);
            }
        }

        private synchronized void remove(Subscriber subscriber) {
            subscribers.remove(subscriber);
        }

        private synchronized int subscriberCount() {
            return subscribers.size();
        }

        private long lastAccessNanos() {
            return lastAccessNanos;
        }

        private synchronized void close() {
            List.copyOf(subscribers).forEach(Subscriber::close);
            subscribers.clear();
        }
    }

    private static final class Subscriber implements Subscription {
        private final SessionState owner;
        private final ArrayBlockingQueue<PushFrame> queue;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile boolean replayGap;
        private volatile boolean overflowed;
        private volatile boolean terminalObserved;

        private Subscriber(SessionState owner, int capacity) {
            this.owner = owner;
            this.queue = new ArrayBlockingQueue<>(capacity);
        }

        private void offer(PushFrame frame) {
            if (closed.get()) {
                return;
            }
            if (!queue.offer(frame)) {
                overflowed = true;
                close();
            }
        }

        @Override
        public PushFrame poll(Duration timeout) throws InterruptedException {
            if (closed.get() && queue.isEmpty()) {
                return null;
            }
            long millis = Math.max(1, timeout == null ? 1_000 : timeout.toMillis());
            return queue.poll(millis, TimeUnit.MILLISECONDS);
        }

        @Override
        public boolean replayGap() {
            return replayGap;
        }

        @Override
        public boolean overflowed() {
            return overflowed;
        }

        @Override
        public boolean terminalObserved() {
            return terminalObserved;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.remove(this);
            }
        }
    }
}
