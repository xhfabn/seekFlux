package io.seekflux.platform.agentruntime.feature;

public interface FeatureNode {

    String name();

    int order();

    void process(FeatureContext context);
}
