package com.migrationsentinel.payload.dto;

import com.migrationsentinel.model.enums.FindingVerdict;

/** A finding after the verification pass. Only CONFIRMED / UNVERIFIED reach the report. */
public record VerifiedFinding(
        ProposedFinding finding,
        FindingVerdict verdict,
        String verifierNote
) {
}
