package io.seekflux.platform.retrieval.semantic;

public interface SemanticVectorEncoder {

    int dimensions();

    String version();

    double[] encode(String text);
}
