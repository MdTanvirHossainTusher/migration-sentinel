package com.migrationsentinel.payload.response;

import java.time.Instant;
import java.util.UUID;

public record PresignedUploadResponse(
        UUID artifactId,
        String objectKey,
        String uploadUrl,
        String method,
        Instant expiresAt,
        long maxBytes
) {
}
