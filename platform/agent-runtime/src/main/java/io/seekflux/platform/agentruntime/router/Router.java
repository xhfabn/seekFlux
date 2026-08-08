package io.seekflux.platform.agentruntime.router;

import io.seekflux.platform.agentruntime.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.feature.FeatureRequest;

public interface Router {

    RouterResult execute(FeatureRequest request, PushEventPublisher publisher);

    boolean cancel(String sessionId, boolean steer);
}
