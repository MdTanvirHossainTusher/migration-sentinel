package com.migrationsentinel.payload.dto;

import java.util.List;

/**
 * The full record of replaying the baseline + running the candidate migration in the
 * disposable sandbox. This is the strongest evidence the reviewer produces.
 */
public record SandboxRunResult(
        boolean baselineApplied,
        boolean candidateApplied,
        long candidateDurationMs,
        String failureMessage,
        List<StatementOutcome> statements,
        List<LockObservation> locks,
        List<TableStat> tableStatsAfter,
        boolean timedOut
) {
    public static SandboxRunResult unavailable(String reason) {
        return new SandboxRunResult(false, false, 0, reason, List.of(), List.of(), List.of(), false);
    }
}
