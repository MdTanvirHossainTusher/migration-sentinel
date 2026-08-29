package com.migrationsentinel.exception;

import org.springframework.http.HttpStatus;

import static com.migrationsentinel.constant.code.ErrorCodes.SANDBOX_SAFETY_VIOLATION;

/**
 * Thrown when a tool attempts DDL against a datasource that is not the
 * per-review disposable sandbox. This is a hard stop — see docs/SAFETY_MODEL.md.
 */
public class SandboxSafetyException extends AppException {
    public SandboxSafetyException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, SANDBOX_SAFETY_VIOLATION, message);
    }
}
