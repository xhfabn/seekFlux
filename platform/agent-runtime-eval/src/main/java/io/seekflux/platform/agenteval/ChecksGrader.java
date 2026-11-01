package io.seekflux.platform.agenteval;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static io.seekflux.platform.agenteval.Evaluation.*;

public final class ChecksGrader implements Grader {
    public String id() { return "checks"; }
    public String version() { return "1"; }
    public List<Finding> grade(Case testCase, Evidence evidence) {
        JsonNode root = EvalJson.tree(evidence.data());
        List<Finding> findings = new ArrayList<>();
        for (Check check : testCase.checks()) {
            JsonNode actual = root.at(check.path()), expected = EvalJson.tree(check.expected());
            if (actual.isMissingNode() && check.operator() != Operator.EXISTS) {
                findings.add(new Finding(check.id(), Status.ERROR, "missing evidence: " + check.path()));
                continue;
            }
            boolean passed = switch (check.operator()) {
                case EXISTS -> !actual.isMissingNode() && !actual.isNull();
                case EQUALS -> equal(actual, expected);
                case SIZE -> actual.isContainerNode() && expected.isIntegralNumber() && actual.size() == expected.intValue();
                case CONTAINS -> actual.isArray() ? contains(actual, expected)
                        : actual.isTextual() && expected.isTextual() && actual.textValue().contains(expected.textValue());
                case AT_MOST -> actual.isNumber() && expected.isNumber()
                        && actual.decimalValue().compareTo(expected.decimalValue()) <= 0;
                case AT_LEAST -> actual.isNumber() && expected.isNumber()
                        && actual.decimalValue().compareTo(expected.decimalValue()) >= 0;
            };
            findings.add(new Finding(check.id(), passed ? Status.PASS : Status.FAIL,
                    check.path() + " " + check.operator() + " expected=" + expected + " actual=" + actual));
        }
        return findings;
    }
    private static boolean equal(JsonNode a, JsonNode b) {
        return a.isNumber() && b.isNumber() ? a.decimalValue().compareTo(b.decimalValue()) == 0 : a.equals(b);
    }
    private static boolean contains(JsonNode array, JsonNode expected) {
        for (var value : array) if (equal(value, expected)) return true;
        return false;
    }
}
