package com.migrationsentinel.payload.response;

import java.util.List;

/** One corpus case, for {@code GET /api/v1/evaluations/cases}. Snake_case on the wire via the primary mapper. */
public record EvaluationCaseSummary(
        String id,
        String title,
        String description,
        boolean hard,
        boolean mustBeClean,
        List<Expected> expected
) {
    public record Expected(String ruleCode, String targetObject) {
    }
}
