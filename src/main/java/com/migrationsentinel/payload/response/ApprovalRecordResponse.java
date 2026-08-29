package com.migrationsentinel.payload.response;

import java.time.Instant;
import java.util.UUID;

public record ApprovalRecordResponse(
        UUID id,
        UUID reviewJobId,
        UUID findingId,
        String action,
        String approvedBy,
        String targetPath,
        boolean applied,
        String note,
        Instant createdAt
) {
}
