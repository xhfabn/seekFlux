package io.seekflux.platform.agentruntime.feature;

import java.util.Comparator;
import java.util.List;

public final class DefaultFeaturePipeline implements FeaturePipeline {

    private final List<FeatureNode> nodes;

    public DefaultFeaturePipeline(List<FeatureNode> nodes) {
        this.nodes = nodes.stream()
                .sorted(Comparator.comparingInt(FeatureNode::order))
                .toList();
        for (int index = 1; index < this.nodes.size(); index++) {
            if (this.nodes.get(index - 1).order() == this.nodes.get(index).order()) {
                throw new IllegalArgumentException("feature node order must be unique");
            }
        }
    }

    @Override
    public FeatureContext process(FeatureRequest request) {
        FeatureContext context = new FeatureContext(request);
        for (FeatureNode node : nodes) {
            node.process(context);
        }
        return context;
    }

    public List<FeatureNode> nodes() {
        return nodes;
    }
}
