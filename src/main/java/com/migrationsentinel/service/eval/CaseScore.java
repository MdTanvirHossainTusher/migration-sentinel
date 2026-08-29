package com.migrationsentinel.service.eval;

public record CaseScore(
        String caseId,
        int expected,
        int reported,
        int truePositives,
        int falsePositives,
        int falseNegatives,
        boolean passed,
        String notes
) {
}
