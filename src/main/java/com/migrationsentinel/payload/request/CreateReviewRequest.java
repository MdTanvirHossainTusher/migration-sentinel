package com.migrationsentinel.payload.request;

import com.migrationsentinel.model.enums.ReviewMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Submit one candidate migration for review")
public record CreateReviewRequest(

        @Schema(example = "V42__add_customer_tier.sql")
        @Size(max = 255)
        String filename,

        @NotBlank
        @Size(max = 200_000)
        @Schema(description = "The candidate Flyway migration SQL", requiredMode = Schema.RequiredMode.REQUIRED)
        String migrationSql,

        @Size(max = 500_000)
        @Schema(description = "Prior migrations replayed into the sandbox before the candidate (concatenated SQL)")
        String baselineSql,

        @Size(max = 500_000)
        @Schema(description = "Seed rows and/or planner-stat setup applied after the baseline "
                + "(e.g. INSERT ..., or UPDATE pg_class SET reltuples=5e7 ...; ANALYZE;)")
        String seedSql,

        @Size(max = 200_000)
        @Schema(description = "JPA entity source (Java) or a JSON mapping spec, for the entity/schema drift check")
        String entitySource,

        @Schema(description = "Which point on the improvement curve to run", defaultValue = "ANALYZER_VERIFIER_SPLIT")
        ReviewMode mode,

        @Schema(description = "LLM provider: heuristic (offline default), openai, or gemini", defaultValue = "heuristic")
        String provider
) {
    public ReviewMode modeOrDefault() {
        return mode == null ? ReviewMode.ANALYZER_VERIFIER_SPLIT : mode;
    }
}
