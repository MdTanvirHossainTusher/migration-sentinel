package com.migrationsentinel.payload.response;

import com.migrationsentinel.model.enums.EvaluationStatus;
import com.migrationsentinel.model.enums.ReviewMode;

import java.time.Instant;
import java.util.UUID;

public record EvaluationRunResponse(
        UUID id,
        EvaluationStatus status,
        ReviewMode mode,
        String provider,
        String corpusLabel,
        int totalCases,
        int completedCases,
        int truePositives,
        int falsePositives,
        int falseNegatives,
        Double precision,
        Double recall,
        Double f1,
        Double falsePositiveRate,
        Long meanDurationMs,
        Instant createdAt,
        Instant finishedAt,
        String errorMessage
) {
}
