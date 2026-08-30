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
        /** Why the sandbox produced no measurements, when it produced none. */
        String sandboxNote,
        /** How many prior migration files were replayed before the candidate. */
        int baselineFileCount,
        int highCount,
        int mediumCount,
        int lowCount,
        String errorMessage
) {
}
