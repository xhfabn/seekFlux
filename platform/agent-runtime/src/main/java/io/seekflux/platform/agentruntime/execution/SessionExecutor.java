package io.seekflux.platform.agentruntime.execution;

import io.seekflux.platform.agentruntime.AgentRunResult;
import io.seekflux.platform.agentruntime.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.loop.AgentLoop;
import io.seekflux.platform.agentruntime.session.AgentSession;
import io.seekflux.platform.agentruntime.session.AgentSessionStore;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class SessionExecutor implements AutoCloseable {

    public static final long AUTHORITY_TTL_MILLIS = 30_000;
    public static final long AUTHORITY_RENEW_MILLIS = 10_000;

    private final ExecutionAuthorityStore authorityStore;
    private final AgentSessionStore sessions;
    private final AgentLoop loop;
    private final ScheduledExecutorService renewalScheduler;
    private final Clock clock;
    private final Map<String, CancellationToken> cancellationTokens = new ConcurrentHashMap<>();
    private volatile boolean closing;

    public SessionExecutor(
            ExecutionAuthorityStore authorityStore,
            AgentSessionStore sessions,
            AgentLoop loop,
            ScheduledExecutorService renewalScheduler,
            Clock clock) {
        this.authorityStore = authorityStore;
        this.sessions = sessions;
        this.loop = loop;
        this.renewalScheduler = renewalScheduler;
        this.clock = clock;
    }

    public java.util.Optional<ExecutionAuthority> tryAcquireExecution(String sessionId) {
        if (closing) {
            return java.util.Optional.empty();
        }
        String owner = UUID.randomUUID().toString();
        return authorityStore.acquire(sessionId, owner, AUTHORITY_TTL_MILLIS);
    }

    public AgentRunResult run(
            String sessionId,
            RuntimeContext context,
            PushEventPublisher publisher,
            ExecutionAuthority authority) {
        if (closing) {
            authority.close();
            throw new IllegalStateException("agent runtime is shutting down");
        }
        CancellationToken token = new CancellationToken();
        cancellationTokens.put(sessionId, token);
        ScheduledFuture<?> renewal = renewalScheduler.scheduleAtFixedRate(
                () -> {
                    if (!authority.renew(AUTHORITY_TTL_MILLIS)) {
                        token.cancel(false);
                    }
                },
                AUTHORITY_RENEW_MILLIS,
                AUTHORITY_RENEW_MILLIS,
                TimeUnit.MILLISECONDS);
        try {
            if (!authority.renew(AUTHORITY_TTL_MILLIS)) {
                token.cancel(false);
                throw new IllegalStateException("agent session execution authority was lost before execution");
            }
            AgentSession fresh = sessions.restoreFresh(sessionId)
                    .orElseThrow(() -> new IllegalStateException("agent session disappeared before execution"));
            AgentRunResult result = loop.run(fresh, context, publisher, token);
            sessions.appendOutcome(sessionId, result, clock.instant());
            return result;
        } finally {
            renewal.cancel(true);
            cancellationTokens.remove(sessionId);
            authority.close();
        }
    }

    public boolean cancel(String sessionId, boolean steer) {
        CancellationToken token = cancellationTokens.get(sessionId);
        if (token == null) {
            return false;
        }
        token.cancel(steer);
        return true;
    }

    @Override
    public void close() {
        closing = true;
        cancellationTokens.values().forEach(token -> token.cancel(false));
        renewalScheduler.shutdownNow();
    }
}
