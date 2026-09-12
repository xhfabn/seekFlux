package io.seekflux.platform.agentruntime.domain.service.feature;

import io.seekflux.platform.agentruntime.domain.model.feature.FeatureContext;
import io.seekflux.platform.agentruntime.application.command.FeatureRequest;

public interface FeaturePipeline {

    FeatureContext process(FeatureRequest request);
}
