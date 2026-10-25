package io.seekflux.platform.agenteval;

import java.util.*;

/** Versioned, JSON-serializable evaluation protocol. No business dataset types. */
public final class Evaluation {
    private Evaluation() {}
    public static final String SCHEMA = "agent-eval/v1";
    public enum Status { PASS, FAIL, ERROR }
    public enum Operator { EQUALS, EXISTS, SIZE, CONTAINS, AT_MOST, AT_LEAST }
    public enum Phase { BEFORE_MODEL, AFTER_MODEL, BEFORE_TOOL, AFTER_TOOL }
    public enum FaultAction { THROW, DELAY, CANCEL }

    public record Check(String id, String path, Operator operator, Object expected) {
        public Check {
            required(id, "check id");
            if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("JSON pointer required");
            Objects.requireNonNull(operator, "check operator");
        }
    }
    public record Fault(String id, Phase phase, String tool, int occurrence,
                        FaultAction action, long delayMillis) {
        public Fault {
            required(id, "fault id"); Objects.requireNonNull(phase); Objects.requireNonNull(action);
            if (occurrence < 1 || delayMillis < 0 || delayMillis > 60_000)
                throw new IllegalArgumentException("invalid fault occurrence/delay");
            if (action == FaultAction.DELAY && delayMillis == 0)
                throw new IllegalArgumentException("delay must be positive");
        }
    }
    public record Case(String id, String input, Set<String> requiredCapabilities,
                       Map<String, Object> parameters, List<Fault> faults, List<Check> checks,
                       long timeoutMillis) {
        public Case {
            required(id, "case id"); Objects.requireNonNull(input, "input");
            requiredCapabilities = Collections.unmodifiableSet(new TreeSet<>(requiredCapabilities));
            parameters = Map.copyOf(parameters); faults = List.copyOf(faults); checks = List.copyOf(checks);
            if (checks.isEmpty() || timeoutMillis < 1 || timeoutMillis > 300_000)
                throw new IllegalArgumentException("checks and a bounded timeout are required");
            unique(faults.stream().map(Fault::id).toList(), "fault");
            unique(checks.stream().map(Check::id).toList(), "check");
        }
    }
    public record Suite(String id, String version, Map<String, String> metadata, List<Case> cases) {
        public Suite {
            required(id, "suite id"); required(version, "suite version");
            metadata = Map.copyOf(metadata); cases = List.copyOf(cases);
            if (cases.isEmpty()) throw new IllegalArgumentException("suite cannot be empty");
            unique(cases.stream().map(Case::id).toList(), "case");
        }
    }
    /** Values are JSON data: traces, full messages/events, effect snapshots, usage and output. */
    public record Evidence(Map<String, Object> data) {
        public Evidence { data = Collections.unmodifiableMap(new LinkedHashMap<>(data)); }
    }
    public record Finding(String id, Status status, String detail) {
        public Finding { required(id, "finding id"); Objects.requireNonNull(status); Objects.requireNonNull(detail); }
    }
    public record CaseResult(String caseId, Status status, long tookMillis,
                             List<Finding> findings, Evidence evidence, Map<String, Integer> faultHits) {
        public CaseResult {
            findings = List.copyOf(findings); faultHits = Map.copyOf(faultHits);
        }
    }
    public record Report(String schema, String runId, String createdAt, String suiteId, String suiteVersion,
                         String suiteHash, String target, String targetVersion, Map<String, String> metadata,
                         List<String> graders, List<CaseResult> results) {
        public Report {
            if (!SCHEMA.equals(schema)) throw new IllegalArgumentException("unsupported report schema: " + schema);
            metadata = Map.copyOf(metadata); graders = List.copyOf(graders); results = List.copyOf(results);
            unique(results.stream().map(CaseResult::caseId).toList(), "result");
        }
        public boolean passed() { return !results.isEmpty() && results.stream().allMatch(r -> r.status() == Status.PASS); }
        public long count(Status status) { return results.stream().filter(r -> r.status() == status).count(); }
    }
    static void required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
    static void unique(List<String> values, String name) {
        if (new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException("duplicate " + name + " id");
    }
}
