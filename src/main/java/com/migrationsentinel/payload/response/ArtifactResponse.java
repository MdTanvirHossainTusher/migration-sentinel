package com.migrationsentinel.payload.response;

import java.util.UUID;

public record ArtifactResponse(
        UUID id,
        String kind,
        String status,
        String filename,
        String contentType,
        Long sizeBytes,
        String downloadUrl
) {
}
