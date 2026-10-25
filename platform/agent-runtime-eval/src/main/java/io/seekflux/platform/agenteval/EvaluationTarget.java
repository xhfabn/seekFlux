package io.seekflux.platform.agenteval;

import java.util.Set;

/** Each case gets a fresh session. Setup, execution and cleanup are inside the case timeout. */
public interface EvaluationTarget {
    String id();
    String version();
    Set<String> capabilities();
    Session open(Evaluation.Case testCase, FaultController faults) throws Exception;
    interface Session extends AutoCloseable {
        Evaluation.Evidence execute() throws Exception;
        @Override void close() throws Exception;
    }
}

