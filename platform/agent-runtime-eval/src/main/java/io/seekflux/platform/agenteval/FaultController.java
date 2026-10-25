package io.seekflux.platform.agenteval;

import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static io.seekflux.platform.agenteval.Evaluation.*;

/** One controller per case: deterministic occurrence matching, never global fault state. */
public final class FaultController {
    private final List<Fault> specs;
    private final Map<String, AtomicInteger> occurrences = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    public FaultController(List<Fault> specs) {
        this.specs = List.copyOf(specs);
        specs.forEach(f -> { occurrences.put(f.id(), new AtomicInteger()); hits.put(f.id(), new AtomicInteger()); });
    }
    public void at(Phase phase, String tool, CancellationToken token) {
        for (Fault spec : specs) {
            if (spec.phase() != phase || spec.tool() != null && !spec.tool().equals(tool)) continue;
            if (occurrences.get(spec.id()).incrementAndGet() != spec.occurrence()) continue;
            hits.get(spec.id()).incrementAndGet();
            switch (spec.action()) {
                case THROW -> throw new InjectedFailure(spec.id());
                case CANCEL -> { token.cancel(false); token.throwIfCancelled(); }
                case DELAY -> {
                    try { Thread.sleep(spec.delayMillis()); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InjectedFailure(spec.id()); }
                }
            }
        }
    }
    public Map<String, Integer> hits() {
        Map<String, Integer> result = new TreeMap<>();
        hits.forEach((key, value) -> result.put(key, value.get())); return Map.copyOf(result);
    }
    public static final class InjectedFailure extends RuntimeException {
        public InjectedFailure(String id) { super("injected fault: " + id); }
    }
}

