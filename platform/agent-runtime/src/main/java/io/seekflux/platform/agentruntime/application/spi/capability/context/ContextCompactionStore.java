package io.seekflux.platform.agentruntime.application.spi.capability.context;

import io.seekflux.platform.agentruntime.domain.model.context.CompactionSummary;
import java.util.Optional;

public interface ContextCompactionStore {

    ContextCompactionStore NOOP = new ContextCompactionStore() {
        @Override
        public Optional<CompactionSummary> latest(String sessionId) {
            return Optional.empty();
        }

        @Override
        public CompactionSummary append(CompactionSummary summary) {
            return summary;
        }
    };

    Optional<CompactionSummary> latest(String sessionId);

    CompactionSummary append(CompactionSummary summary);
}
