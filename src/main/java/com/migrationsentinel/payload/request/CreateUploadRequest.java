package com.migrationsentinel.payload.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "Ask for a presigned URL to upload one file directly to object storage")
public record CreateUploadRequest(

        @NotBlank
        @Size(max = 255)
        @Schema(example = "identity-migrations.zip")
        String filename,

        @Size(max = 128)
        @Schema(example = "application/zip")
        String contentType,

        @Positive
        @Schema(description = "Exact size of the file, checked against the server limit now and again on confirm",
                requiredMode = Schema.RequiredMode.REQUIRED)
        long sizeBytes
) {
}
