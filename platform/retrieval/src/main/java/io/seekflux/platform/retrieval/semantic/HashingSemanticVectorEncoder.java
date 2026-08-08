package io.seekflux.platform.retrieval.semantic;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class HashingSemanticVectorEncoder implements SemanticVectorEncoder {

    private static final String VERSION = "hashing-char-ngram-v1";
    private final int dimensions;

    public HashingSemanticVectorEncoder(
            @Value("${seekflux.search.semantic.dimensions:64}") int dimensions) {
        if (dimensions < 16 || dimensions > 2048) {
            throw new IllegalArgumentException("semantic vector dimensions must be between 16 and 2048");
        }
        this.dimensions = dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public double[] encode(String text) {
        String normalized = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
        double[] vector = new double[dimensions];
        for (String feature : features(normalized)) {
            int hash = feature.hashCode();
            int index = Math.floorMod(hash, dimensions);
            double sign = (Integer.rotateLeft(hash, 13) & 1) == 0 ? 1.0 : -1.0;
            vector[index] += sign;
        }
        double norm = 0;
        for (double value : vector) {
            norm += value * value;
        }
        if (norm == 0) {
            vector[0] = 1.0;
            return vector;
        }
        double divisor = Math.sqrt(norm);
        for (int index = 0; index < vector.length; index++) {
            vector[index] /= divisor;
        }
        return vector;
    }

    private static List<String> features(String text) {
        List<String> features = new ArrayList<>();
        for (String token : text.split("[^\\p{L}\\p{N}]+")) {
            if (token.isBlank()) {
                continue;
            }
            features.add("token:" + token);
            int[] codePoints = token.codePoints().toArray();
            for (int width : List.of(2, 3)) {
                for (int index = 0; index + width <= codePoints.length; index++) {
                    features.add("ngram:" + new String(codePoints, index, width));
                }
            }
        }
        return features;
    }
}
