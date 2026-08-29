package com.migrationsentinel.exception;

import com.migrationsentinel.payload.common.ErrorDetail;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.List;

@Getter
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient List<ErrorDetail> details;

    public AppException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public AppException(HttpStatus status, String code, String message, List<ErrorDetail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }
}
