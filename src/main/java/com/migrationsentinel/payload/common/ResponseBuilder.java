package com.migrationsentinel.payload.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static com.migrationsentinel.constant.code.ErrorCodes.BAD_REQUEST;
import static com.migrationsentinel.constant.code.ErrorCodes.CONFLICT;
import static com.migrationsentinel.constant.code.ErrorCodes.INTERNAL_ERROR;
import static com.migrationsentinel.constant.code.ErrorCodes.NOT_FOUND;
import static com.migrationsentinel.constant.code.ErrorCodes.VALIDATION_ERROR;

/**
 * Central factory for every API response. Success bodies always carry
 * {@code success:true}; error bodies always carry a populated {@code error} object.
 */
public final class ResponseBuilder {

    private ResponseBuilder() {
    }

    // ── Success ──────────────────────────────────────────────────────────────

    public static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return build(HttpStatus.OK, data, null, null, null);
    }

    public static <T> ResponseEntity<ApiResponse<T>> ok(T data, String code, String message) {
        return build(HttpStatus.OK, data, null, code, message);
    }

    public static <T> ResponseEntity<ApiResponse<T>> ok(T data, Pagination pagination) {
        return build(HttpStatus.OK, data, pagination, null, null);
    }

    public static <T> ResponseEntity<ApiResponse<T>> created(T data, String code, String message) {
        return build(HttpStatus.CREATED, data, null, code, message);
    }

    public static <T> ResponseEntity<ApiResponse<T>> accepted(T data, String code, String message) {
        return build(HttpStatus.ACCEPTED, data, null, code, message);
    }

    private static <T> ResponseEntity<ApiResponse<T>> build(
            HttpStatus status, T data, Pagination pagination, String code, String message) {
        return ResponseEntity.status(status)
                .body(ApiResponse.success(code, message, data, pagination, ResponseMetadata.now()));
    }

    // ── Error ────────────────────────────────────────────────────────────────

    public static <T> ResponseEntity<ApiResponse<T>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiResponse.failure(ErrorResponse.of(code, message)));
    }

    public static <T> ResponseEntity<ApiResponse<T>> error(
            HttpStatus status, String code, String message, List<ErrorDetail> details) {
        return ResponseEntity.status(status).body(ApiResponse.failure(ErrorResponse.of(code, message, details)));
    }

    public static <T> ResponseEntity<ApiResponse<T>> validationError(List<ErrorDetail> details) {
        String message = details.size() == 1
                ? details.get(0).message()
                : details.size() + " validation errors occurred";
        return error(HttpStatus.UNPROCESSABLE_ENTITY, VALIDATION_ERROR, message, details);
    }

    public static <T> ResponseEntity<ApiResponse<T>> notFound(String message) {
        return error(HttpStatus.NOT_FOUND, NOT_FOUND, message);
    }

    public static <T> ResponseEntity<ApiResponse<T>> badRequest(String message) {
        return error(HttpStatus.BAD_REQUEST, BAD_REQUEST, message);
    }

    public static <T> ResponseEntity<ApiResponse<T>> conflict(String message) {
        return error(HttpStatus.CONFLICT, CONFLICT, message);
    }

    public static <T> ResponseEntity<ApiResponse<T>> internalError(String message) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, message);
    }
}
