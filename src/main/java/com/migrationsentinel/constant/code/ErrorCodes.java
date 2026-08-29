package com.migrationsentinel.constant.code;

public final class ErrorCodes {

    private ErrorCodes() {
    }

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String CONFLICT = "CONFLICT";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /** The agent (or a tool) tried to reach a database outside the disposable sandbox. */
    public static final String SANDBOX_SAFETY_VIOLATION = "SANDBOX_SAFETY_VIOLATION";

    /** The Docker daemon needed for the Testcontainers sandbox is not reachable. */
    public static final String SANDBOX_UNAVAILABLE = "SANDBOX_UNAVAILABLE";

    /** A configured LLM provider rejected the request or is unreachable. */
    public static final String LLM_PROVIDER_ERROR = "LLM_PROVIDER_ERROR";

    /** The requested evaluation case corpus could not be loaded. */
    public static final String EVAL_CORPUS_ERROR = "EVAL_CORPUS_ERROR";
}
