package com.migrationsentinel.payload.response;

import com.migrationsentinel.model.enums.FindingVerdict;
import com.migrationsentinel.model.enums.RuleCode;
import com.migrationsentinel.model.enums.Severity;

import java.util.UUID;

public record FindingResponse(
        UUID id,
        int ordinal,
        RuleCode ruleCode,
        Severity severity,
        String title,
        String targetObject,
        String summary,
        String evidence,
        String suggestedRewrite,
        FindingVerdict verdict,
        Double analyzerConfidence
) {
}
