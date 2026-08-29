package com.migrationsentinel.payload.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * The one consequential action the system exposes. It writes a suggested rewrite to a
 * file under the configured output directory — and only when a human explicitly confirms.
 * See docs/SAFETY_MODEL.md.
 */
@Schema(description = "Human-approved request to write a suggested rewrite to disk")
public record ApplyRewriteRequest(

        @NotNull
        @Schema(description = "Which finding's suggested rewrite to write", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID findingId,

        @NotBlank
        @Schema(description = "Relative filename to write under sentinel.rewrite-output-dir", example = "V42__add_customer_tier.fixed.sql")
        String targetFilename,

        @NotBlank
        @Schema(description = "Who approved this (name or email) — recorded verbatim in the audit trail")
        String approvedBy,

        @Schema(description = "Optional note stored with the approval")
        String note,

        @Schema(description = "Must be true — the explicit human confirmation", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean confirm
) {
    @AssertTrue(message = "confirm must be true to apply a rewrite")
    public boolean isConfirmed() {
        return confirm;
    }
}
