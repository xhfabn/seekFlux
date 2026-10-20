package io.seekflux.core.adapter.elasticsearch;

public interface SemanticVectorEncoder {

    int dimensions();

    String version();

    double[] encode(String text);
}
