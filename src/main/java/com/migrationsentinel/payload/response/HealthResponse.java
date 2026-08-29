package com.migrationsentinel.payload.response;

import java.time.Instant;
import java.util.List;

public record HealthResponse(
        String status,
        String version,
        String defaultProvider,
        List<String> availableProviders,
        boolean dockerAvailable,
        int evaluationCaseCount,
        Instant timestamp
) {
}
