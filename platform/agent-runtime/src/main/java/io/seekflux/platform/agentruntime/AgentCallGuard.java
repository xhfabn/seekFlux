package io.seekflux.platform.agentruntime;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

public final class AgentCallGuard {

    public static final AgentCallGuard UNBOUNDED = new AgentCallGuard(
            Integer.MAX_VALUE, Integer.MAX_VALUE, FaultInjector.NONE);

    private final Semaphore modelBulkhead;
    private final Semaphore toolBulkhead;
    private final FaultInjector faultInjector;

    public AgentCallGuard(int maxConcurrentModelCalls, int maxConcurrentToolCalls, FaultInjector faultInjector) {
        if (maxConcurrentModelCalls < 1 || maxConcurrentToolCalls < 1) {
            throw new IllegalArgumentException("Agent bulkhead limits must be positive");
        }
        this.modelBulkhead = new Semaphore(maxConcurrentModelCalls);
        this.toolBulkhead = new Semaphore(maxConcurrentToolCalls);
        this.faultInjector = faultInjector == null ? FaultInjector.NONE : faultInjector;
    }

    public <T> T execute(CallType type, Callable<T> action) throws Exception {
        Semaphore semaphore = type == CallType.MODEL ? modelBulkhead : toolBulkhead;
        if (!semaphore.tryAcquire()) {
            throw new CallRejectedException(type == CallType.MODEL
                    ? "MODEL_BULKHEAD_FULL"
                    : "TOOL_BULKHEAD_FULL");
        }
        try {
            faultInjector.before(type);
            return action.call();
        } finally {
            semaphore.release();
        }
    }

    public enum CallType { MODEL, TOOL }

    @FunctionalInterface
    public interface FaultInjector {
        FaultInjector NONE = ignored -> { };
        void before(CallType type);
    }

    public static final class CallRejectedException extends RuntimeException {
        private final String code;

        public CallRejectedException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
