package io.seekflux.platform.agentruntime.application.api;

import io.seekflux.platform.agentruntime.application.api.model.RouterResult;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResolution;

@FunctionalInterface
public interface WaitResumeDispatcher {

    RouterResult dispatch(WaitResolution resolution, PushEventPublisher publisher);
}
