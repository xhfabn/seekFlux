package io.seekflux.platform.agentruntime.application.api;

import io.seekflux.platform.agentruntime.application.api.model.RouterResult;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.application.command.FeatureRequest;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResolution;

public interface Router {

    RouterResult execute(FeatureRequest request, PushEventPublisher publisher);

    default RouterResult resume(
            WaitResolution resolution,
            FeatureRequest request,
            PushEventPublisher publisher) {
        throw new UnsupportedOperationException("Agent wait resume is not configured");
    }

    boolean cancel(String sessionId, boolean steer);
}
