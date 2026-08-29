package com.migrationsentinel.payload.response;

import java.util.UUID;

public record EvaluationCaseResultResponse(
        String caseId,
        int expectedCount,
        int reportedCount,
        int truePositives,
        int falsePositives,
        int falseNegatives,
        boolean passed,
        String notes,
        UUID reviewJobId
) {
}
