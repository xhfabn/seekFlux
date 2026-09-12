package io.seekflux.platform.agentruntime.application.api;

import io.seekflux.platform.agentruntime.application.api.model.RouterResult;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.application.command.FeatureRequest;

public interface Router {

    RouterResult execute(FeatureRequest request, PushEventPublisher publisher);

    boolean cancel(String sessionId, boolean steer);
}
