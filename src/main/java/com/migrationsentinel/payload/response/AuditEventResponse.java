package com.migrationsentinel.payload.response;

import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,
        String eventType,
        String aggregateType,
        String aggregateId,
        String actor,
        String summary,
        String payload,
        Instant createdAt
) {
}
