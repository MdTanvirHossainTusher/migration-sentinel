package com.migrationsentinel.payload.dto;

import java.util.List;

/** What the orchestrator returns to {@code ReviewService} once a review completes. */
public record ReviewOutcome(
        List<VerifiedFinding> findings,
        String reportMarkdown,
        boolean sandboxUsed
) {
}
