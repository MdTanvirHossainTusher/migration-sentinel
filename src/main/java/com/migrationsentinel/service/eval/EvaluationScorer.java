package com.migrationsentinel.service.eval;

import com.migrationsentinel.model.entity.FindingEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Scores one review against a case's labels.
 *
 * <p>A reported finding is a true positive when its rule code matches an unmatched
 * expected label, the objects match on the leading identifier, and — when the label
 * pins a severity — the severity matches. Everything else reported is a false positive;
 * every unmatched label is a false negative.
 *
 * <p>The severity check is what separates a tool-using agent from a prompt: the same
 * {@code SET NOT NULL} is HIGH on a 5M-row table and LOW on an empty one, and only the
 * agent that measured the table gets the severity right.
 *
 * <p>A case passes with no false negatives and — for "must be clean" cases — no false
 * positives, or at most one otherwise. See docs/EVALUATION.md.
 */
@Component
public class EvaluationScorer {

    public CaseScore score(EvaluationCase testCase, List<FindingEntity> reported) {
        List<EvaluationCase.ExpectedFinding> unmatchedExpected = new ArrayList<>(testCase.expected());
        int truePositives = 0;
        int falsePositives = 0;
        List<String> notes = new ArrayList<>();

        for (FindingEntity finding : reported) {
            EvaluationCase.ExpectedFinding match = null;
            for (EvaluationCase.ExpectedFinding exp : unmatchedExpected) {
                if (exp.ruleCode() == finding.getRuleCode()
                        && objectMatches(exp.targetObject(), finding.getTargetObject())
                        && severityMatches(exp.severity(), finding.getSeverity())) {
                    match = exp;
                    break;
                }
            }
            if (match != null) {
                unmatchedExpected.remove(match);
                truePositives++;
            } else {
                falsePositives++;
                notes.add("FP: " + finding.getRuleCode() + " " + finding.getSeverity()
                        + " on " + finding.getTargetObject());
            }
        }
        for (EvaluationCase.ExpectedFinding miss : unmatchedExpected) {
            notes.add("FN: " + miss.ruleCode()
                    + (miss.severity() == null ? "" : " " + miss.severity())
                    + (miss.targetObject() == null ? "" : " on " + miss.targetObject()));
        }
        int falseNegatives = unmatchedExpected.size();
        int fpAllowance = testCase.mustBeClean() ? 0 : 1;
        boolean passed = falseNegatives == 0 && falsePositives <= fpAllowance;

        return new CaseScore(
                testCase.id(),
                testCase.expectedCount(),
                reported.size(),
                truePositives,
                falsePositives,
                falseNegatives,
                passed,
                notes.isEmpty() ? "exact match" : String.join("; ", notes));
    }

    private boolean severityMatches(Object expected, Object actual) {
        return expected == null || expected == actual;
    }

    private boolean objectMatches(String expected, String actual) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        return leading(expected).equalsIgnoreCase(leading(actual))
                || actual.toLowerCase().contains(expected.toLowerCase());
    }

    private String leading(String object) {
        return object.trim().toLowerCase().split("[\\s(]")[0];
    }
}
