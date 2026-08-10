package io.seekflux.platform.agentruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class AgentCallGuardTest {

    @Test
    void rejectsASecondModelCallWhenTheBulkheadIsFull() throws Exception {
        AgentCallGuard guard = new AgentCallGuard(1, 1, AgentCallGuard.FaultInjector.NONE);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> guard.execute(AgentCallGuard.CallType.MODEL, () -> {
                entered.countDown();
                release.await();
                return "done";
            }));
            entered.await();
            AgentCallGuard.CallRejectedException rejected = assertThrows(
                    AgentCallGuard.CallRejectedException.class,
                    () -> guard.execute(AgentCallGuard.CallType.MODEL, () -> "second"));
            assertEquals("MODEL_BULKHEAD_FULL", rejected.code());
            release.countDown();
            assertEquals("done", first.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
