package io.seekflux.apps.agentserver.bootstrap;

import io.seekflux.platform.agentruntime.domain.service.wait.WaitTimeoutProcessor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

public final class AgentWaitTimeoutWorker {

    private final WaitTimeoutProcessor processor;
    private final int batchSize;
    private final ExecutorService executor;
    private final AtomicBoolean scanInFlight = new AtomicBoolean();

    public AgentWaitTimeoutWorker(
            WaitTimeoutProcessor processor,
            int batchSize,
            ExecutorService executor) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException(
                    "wait timeout batch size must be between 1 and 1000");
        }
        this.processor = processor;
        this.batchSize = batchSize;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${seekflux.agent.wait.timeout-scan-ms:1000}")
    public void scan() {
        if (!scanInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    processor.processExpired(batchSize);
                } finally {
                    scanInFlight.set(false);
                }
            });
        } catch (RejectedExecutionException saturated) {
            scanInFlight.set(false);
        }
    }
}
