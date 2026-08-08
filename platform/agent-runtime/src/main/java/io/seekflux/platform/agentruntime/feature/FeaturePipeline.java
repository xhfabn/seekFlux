package io.seekflux.platform.agentruntime.feature;

public interface FeaturePipeline {

    FeatureContext process(FeatureRequest request);
}
