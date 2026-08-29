package com.migrationsentinel.exception;

import org.springframework.http.HttpStatus;

import static com.migrationsentinel.constant.code.ErrorCodes.LLM_PROVIDER_ERROR;

/** Thrown when a configured LLM provider rejects a request or is unreachable. */
public class LlmProviderException extends AppException {
    public LlmProviderException(String message) {
        super(HttpStatus.BAD_GATEWAY, LLM_PROVIDER_ERROR, message);
    }
}
