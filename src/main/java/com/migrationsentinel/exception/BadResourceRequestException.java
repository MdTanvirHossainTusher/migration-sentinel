package com.migrationsentinel.exception;

import org.springframework.http.HttpStatus;

import static com.migrationsentinel.constant.code.ErrorCodes.BAD_REQUEST;

public class BadResourceRequestException extends AppException {
    public BadResourceRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, BAD_REQUEST, message);
    }
}
