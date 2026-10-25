package io.seekflux.platform.agenteval.junit;

import io.seekflux.platform.agenteval.Evaluation;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class AgentEvaluations {
    private AgentEvaluations() {}
    /** Execute the suite once, then expose every result as an independently named JUnit test. */
    public static Stream<DynamicTest> tests(Evaluation.Report report) {
        if (report.results().isEmpty()) throw new IllegalArgumentException("empty evaluation report");
        return report.results().stream().map(result -> DynamicTest.dynamicTest(result.caseId(),
                () -> assertEquals(Evaluation.Status.PASS, result.status(), () -> result.findings().toString())));
    }
}

