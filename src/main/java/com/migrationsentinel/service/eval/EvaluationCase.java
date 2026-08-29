package com.migrationsentinel.service.eval;

import com.migrationsentinel.model.enums.RuleCode;
import com.migrationsentinel.model.enums.Severity;

import java.util.List;

/** One migration-safety test case loaded from {@code eval/cases/<id>/}. */
public record EvaluationCase(
        String id,
        String title,
        String description,
        boolean hard,
        String migrationSql,
        String baselineSql,
        String seedSql,
        String entitySource,
        List<ExpectedFinding> expected,
        boolean mustBeClean
) {
    public record ExpectedFinding(RuleCode ruleCode, String targetObject, Severity severity, String note) {
    }

    public int expectedCount() {
        return mustBeClean ? 0 : expected.size();
    }
}
