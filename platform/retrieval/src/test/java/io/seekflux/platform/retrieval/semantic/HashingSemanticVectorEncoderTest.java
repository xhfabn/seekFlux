package io.seekflux.platform.retrieval.semantic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HashingSemanticVectorEncoderTest {

    private final HashingSemanticVectorEncoder encoder = new HashingSemanticVectorEncoder(64);

    @Test
    void producesDeterministicNormalizedVectors() {
        double[] first = encoder.encode("杭州亲子露营路线");
        double[] second = encoder.encode("杭州亲子露营路线");

        assertArrayEquals(first, second);
        assertEquals(64, first.length);
        double norm = 0;
        for (double value : first) {
            norm += value * value;
        }
        assertTrue(Math.abs(1.0 - norm) < 0.000001);
    }

    @Test
    void normalizesCompatibilityCharacters() {
        assertArrayEquals(encoder.encode("ＡＩ 办公"), encoder.encode("AI 办公"));
    }
}
