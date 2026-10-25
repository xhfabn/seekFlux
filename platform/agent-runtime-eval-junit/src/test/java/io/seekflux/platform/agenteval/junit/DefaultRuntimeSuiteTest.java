package io.seekflux.platform.agenteval.junit;

import io.seekflux.platform.agenteval.*;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;

class DefaultRuntimeSuiteTest {
    @TestFactory Stream<DynamicTest> defaultRuntimeSuite() throws Exception {
        var report = EvalCli.runner().run(DefaultSuite.load(), EvalCli.target());
        ReportWriter.write(Path.of("target/evaluation"), report);
        return AgentEvaluations.tests(report);
    }
}
