package com.migrationsentinel.payload.common;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A single field- or resource-level error")
public record ErrorDetail(
        String field,
        String type,
        String message
) {
    public static ErrorDetail of(String field, String type, String message) {
        return new ErrorDetail(field, type, message);
    }
}
