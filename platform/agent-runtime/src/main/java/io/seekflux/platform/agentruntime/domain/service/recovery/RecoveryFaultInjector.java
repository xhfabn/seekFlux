package io.seekflux.platform.agentruntime.domain.service.recovery;

@FunctionalInterface
public interface RecoveryFaultInjector {

    RecoveryFaultInjector NONE = point -> { };

    void at(RecoveryPoint point);
}
