package com.migrationsentinel.payload.response;

import com.migrationsentinel.model.enums.ReviewMode;
import com.migrationsentinel.model.enums.ReviewStatus;

import java.time.Instant;
import java.util.UUID;

public record ReviewResponse(
        UUID id,
        ReviewStatus status,
        ReviewMode mode,
        String provider,
        String filename,
        String caseId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        int findingsCount,
        int toolCallCount,
        boolean sandboxUsed,
        int highCount,
        int mediumCount,
        int lowCount,
        String errorMessage
) {
}
