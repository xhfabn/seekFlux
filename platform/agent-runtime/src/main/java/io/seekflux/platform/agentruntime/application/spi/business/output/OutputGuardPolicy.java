package io.seekflux.platform.agentruntime.application.spi.business.output;

public record OutputGuardPolicy(int maxRepairAttempts, ExhaustedAction exhaustedAction) {

    public static final OutputGuardPolicy REPAIR_THEN_DEGRADE =
            new OutputGuardPolicy(1, ExhaustedAction.DEGRADE);

    public OutputGuardPolicy {
        if (maxRepairAttempts < 0 || maxRepairAttempts > 3) {
            throw new IllegalArgumentException("output repair attempts must be between 0 and 3");
        }
        exhaustedAction = exhaustedAction == null ? ExhaustedAction.DEGRADE : exhaustedAction;
    }

    public enum ExhaustedAction {
        DEGRADE,
        FAIL
    }
}
