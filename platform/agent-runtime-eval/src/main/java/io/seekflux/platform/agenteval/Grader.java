package io.seekflux.platform.agenteval;

import java.util.List;

/** Domain graders consume evidence; they never need to depend on the fixture implementation. */
public interface Grader {
    String id();
    String version();
    List<Evaluation.Finding> grade(Evaluation.Case testCase, Evaluation.Evidence evidence) throws Exception;
}
