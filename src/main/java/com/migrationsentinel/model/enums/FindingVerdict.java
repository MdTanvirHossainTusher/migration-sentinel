package com.migrationsentinel.model.enums;

/** The verifier's judgement on a finding proposed by the analyzer. */
public enum FindingVerdict {
    /** Tool evidence supports the finding. It appears in the final report. */
    CONFIRMED,
    /** No tool output backs the claim, or a tool contradicts it. Dropped from the report. */
    REJECTED,
    /** Plausible but unproven at the scale of this sandbox. Kept, flagged as unverified. */
    UNVERIFIED
}
