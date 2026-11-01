package io.seekflux.platform.agentruntime.infrastructure.observation;

import io.seekflux.platform.agentruntime.application.spi.capability.observation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded, best-effort transport. Does not replace the recovery journal or a durable outbox. */
public final class AsyncObservationExporter implements AgentExecutionObserver, AutoCloseable {
    private final AgentObservationExporter exporter;
    private final ArrayBlockingQueue<AgentObservationEvent> queue;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration shutdownTimeout;
    private final Thread worker;
    private volatile boolean closed;
    private final Object lifecycle = new Object();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder exported = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final AtomicInteger inFlight = new AtomicInteger();

    public AsyncObservationExporter(AgentObservationExporter exporter, int capacity, int batchSize,
            int maxAttempts, Duration shutdownTimeout) {
        if (capacity < 1 || batchSize < 1 || batchSize > capacity || maxAttempts < 1
                || shutdownTimeout.isNegative() || shutdownTimeout.isZero()
                || shutdownTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("invalid observation export limits");
        }
        this.exporter = Objects.requireNonNull(exporter);
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.shutdownTimeout = shutdownTimeout;
        worker = new Thread(this::drain, "seekflux-observation-export");
        worker.setDaemon(true);
        worker.start();
    }

    @Override public void onEvent(AgentObservationEvent event) {
        Objects.requireNonNull(event);
        synchronized (lifecycle) {
            if (closed || !queue.offer(event)) dropped.increment();
        }
    }

    public Statistics statistics() {
        return new Statistics(queue.size(), inFlight.get(), exported.sum(), dropped.sum(), failures.sum());
    }

    public record Statistics(int queued, int inFlight, long exported, long dropped, long failedAttempts) { }

    private void drain() {
        try {
            while (!closed || !queue.isEmpty()) {
                AgentObservationEvent first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                List<AgentObservationEvent> batch = new ArrayList<>(batchSize);
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                List<AgentObservationEvent> immutable = List.copyOf(batch);
                inFlight.set(immutable.size());
                boolean delivered = false;
                try {
                    for (int attempt = 0; attempt < maxAttempts; attempt++) {
                        try {
                            exporter.export(immutable);
                            exported.add(immutable.size());
                            delivered = true;
                            break;
                        } catch (InterruptedException interrupted) {
                            throw interrupted;
                        } catch (Exception failure) {
                            failures.increment();
                            if (attempt + 1 < maxAttempts) Thread.sleep(Math.min(1000, 100L * (attempt + 1)));
                        }
                    }
                } finally {
                    if (!delivered) dropped.add(immutable.size());
                    inFlight.set(0);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (lifecycle) {
                closed = true;
                dropped.add(queue.size());
                queue.clear();
            }
        }
    }

    /** Drains until deadline; interrupts a stuck exporter without blocking application shutdown forever. */
    @Override public void close() {
        synchronized (lifecycle) { closed = true; }
        if (Thread.currentThread() == worker) return;
        try { worker.join(shutdownTimeout.toMillis()); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (worker.isAlive()) worker.interrupt();
    }
}
