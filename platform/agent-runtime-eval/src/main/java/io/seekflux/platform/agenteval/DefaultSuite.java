package io.seekflux.platform.agenteval;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class DefaultSuite {
    private DefaultSuite() {}
    public static Evaluation.Suite load() {
        try (var stream = DefaultSuite.class.getResourceAsStream("/agent-eval/default-suite.json")) {
            if (stream == null) throw new IllegalStateException("default suite resource missing");
            return EvalJson.decode(new String(stream.readAllBytes(), StandardCharsets.UTF_8), Evaluation.Suite.class);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}

