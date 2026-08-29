package com.migrationsentinel.payload.dto;

/** The result of executing one statement of the candidate migration in the sandbox. */
public record StatementOutcome(
        int index,
        String sql,
        boolean ok,
        long durationMs,
        String error,
        String strongestLock
) {
}
