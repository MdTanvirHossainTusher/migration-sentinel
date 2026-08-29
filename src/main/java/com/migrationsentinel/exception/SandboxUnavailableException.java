package com.migrationsentinel.exception;

import org.springframework.http.HttpStatus;

import static com.migrationsentinel.constant.code.ErrorCodes.SANDBOX_UNAVAILABLE;

/** Thrown when the Docker daemon required for the Testcontainers sandbox is not reachable. */
public class SandboxUnavailableException extends AppException {
    public SandboxUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, SANDBOX_UNAVAILABLE, message);
    }
}
