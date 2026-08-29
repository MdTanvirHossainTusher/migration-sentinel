package com.migrationsentinel.exception;

import org.springframework.http.HttpStatus;

import static com.migrationsentinel.constant.code.ErrorCodes.NOT_FOUND;

public class ResourceNotFoundException extends AppException {
    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, NOT_FOUND, message);
    }
}
