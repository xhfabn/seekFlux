package io.seekflux.platform.agentruntime.application.spi.business.feature;

import io.seekflux.platform.agentruntime.domain.model.feature.FeatureContext;

public interface FeatureNode {

    String name();

    int order();

    void process(FeatureContext context);
}
