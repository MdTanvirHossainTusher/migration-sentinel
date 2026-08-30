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
        boolean timedOut,

        /**
         * True only when the introspector actually read the schema back. Without this the
         * rules cannot tell "measured, and the table has no index" from "never measured" —
         * and would cite pg_index as evidence for a lookup that never happened.
         */
        boolean schemaObserved,

        /** How far the prior-migration replay got, and which file stopped it. */
        BaselineReplay baseline
) {

    /**
     * Progress and failure attribution for the prior-migration replay. On a 400-file history
     * "sandbox setup failed: relation already exists" is not actionable; the filename is.
     */
    public record BaselineReplay(
            int filesTotal,
            int filesApplied,
            int statementsApplied,
            String failedFile,
            String failedStatement,
            String failureMessage
    ) {
        public static BaselineReplay none() {
            return new BaselineReplay(0, 0, 0, null, null, null);
        }

        public boolean failed() {
            return failureMessage != null;
        }

        /** One line naming where the replay stopped, for the report and the UI banner. */
        public String describeFailure() {
            if (!failed()) {
                return null;
            }
            StringBuilder sb = new StringBuilder("Prior migrations stopped replaying");
            if (failedFile != null) {
                sb.append(" at ").append(failedFile);
            }
            if (filesTotal > 0) {
                sb.append(" (").append(filesApplied).append(" of ").append(filesTotal).append(" files applied)");
            }
            sb.append(": ").append(failureMessage.trim());
            return sb.toString();
        }
    }

    public static SandboxRunResult unavailable(String reason) {
        return new SandboxRunResult(false, false, 0, reason, List.of(), List.of(), List.of(), false,
                false, BaselineReplay.none());
    }

    public BaselineReplay baselineOrNone() {
        return baseline == null ? BaselineReplay.none() : baseline;
    }
}
