package com.migrationsentinel.payload.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.migrationsentinel.model.enums.RuleCode;
import com.migrationsentinel.model.enums.Severity;

/** A finding as proposed by the analyzer, before the verifier has judged it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProposedFinding(
        RuleCode ruleCode,
        Severity severity,
        String title,
        String targetObject,
        String summary,
        String evidence,
        String suggestedRewrite,
        Double confidence
) {
    public ProposedFinding withEvidence(String mergedEvidence) {
        return new ProposedFinding(ruleCode, severity, title, targetObject, summary,
                mergedEvidence, suggestedRewrite, confidence);
    }
}
