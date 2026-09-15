package io.seekflux.platform.agentruntime.domain.model.recovery;

import java.util.Comparator;
import java.util.List;

public record RecoveryPlan(
        ResumeAction action,
        RuntimeCheckpoint checkpoint,
        List<ToolCallJournalEntry> toolCalls) {

    public static final RecoveryPlan START_NEW =
            new RecoveryPlan(ResumeAction.START_NEW, null, List.of());

    public RecoveryPlan {
        if (action == null) {
            throw new IllegalArgumentException("resume action must not be null");
        }
        toolCalls = toolCalls == null ? List.of() : toolCalls.stream()
                .sorted(Comparator.comparingInt(ToolCallJournalEntry::callIndex))
                .toList();
        if (action != ResumeAction.START_NEW && checkpoint == null) {
            throw new IllegalArgumentException("a resume action requires a checkpoint");
        }
    }
}
