package com.migrationsentinel.exception;

import com.migrationsentinel.payload.common.ApiResponse;
import com.migrationsentinel.payload.common.ErrorDetail;
import com.migrationsentinel.payload.common.ResponseBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static com.migrationsentinel.constant.code.ErrorCodes.VALIDATION_ERROR;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<ErrorDetail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> ErrorDetail.of(err.getField(), VALIDATION_ERROR, err.getDefaultMessage()))
                .toList();
        return ResponseBuilder.validationError(details);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseBuilder.validationError(
                List.of(ErrorDetail.of(ex.getParameterName(), VALIDATION_ERROR,
                        "Required parameter '" + ex.getParameterName() + "' is missing")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseBuilder.validationError(
                List.of(ErrorDetail.of(ex.getName(), VALIDATION_ERROR,
                        "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'")));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseBuilder.badRequest("The uploaded file is too large.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNoResource(NoResourceFoundException ex) {
        return ResponseBuilder.notFound("No endpoint for " + ex.getResourcePath());
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Object>> handleAppException(AppException ex) {
        log.warn("[{}] {}", ex.getCode(), ex.getMessage());
        if (ex.getDetails() != null && !ex.getDetails().isEmpty()) {
            return ResponseBuilder.error(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getDetails());
        }
        return ResponseBuilder.error(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseBuilder.internalError("An unexpected error occurred. Please try again later.");
    }
}
