package io.seekflux.platform.agenteval;

import java.util.*;
import static io.seekflux.platform.agenteval.Evaluation.*;

public record ReportComparison(String baselineRun, String candidateRun,
                               List<String> regressions, List<String> improvements,
                               long baselinePassed, long candidatePassed, int total) {
    public static ReportComparison compare(Report baseline, Report candidate) {
        if (!baseline.suiteHash().equals(candidate.suiteHash()) || !baseline.suiteId().equals(candidate.suiteId())
                || !baseline.suiteVersion().equals(candidate.suiteVersion()) || !baseline.graders().equals(candidate.graders())
                || !baseline.target().equals(candidate.target()))
            throw new IllegalArgumentException("incomparable suite, graders or target identity");
        Map<String, Status> before = new TreeMap<>(), after = new TreeMap<>();
        baseline.results().forEach(r -> before.put(r.caseId(), r.status()));
        candidate.results().forEach(r -> after.put(r.caseId(), r.status()));
        if (before.isEmpty() || !before.keySet().equals(after.keySet()))
            throw new IllegalArgumentException("incomparable case sets");
        List<String> regressions = new ArrayList<>(), improvements = new ArrayList<>();
        before.forEach((id, state) -> {
            if (state == Status.PASS && after.get(id) != Status.PASS) regressions.add(id);
            if (state != Status.PASS && after.get(id) == Status.PASS) improvements.add(id);
        });
        return new ReportComparison(baseline.runId(), candidate.runId(), List.copyOf(regressions),
                List.copyOf(improvements), baseline.count(Status.PASS), candidate.count(Status.PASS), before.size());
    }
}

