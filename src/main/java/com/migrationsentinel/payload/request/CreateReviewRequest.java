package com.migrationsentinel.payload.request;

import com.migrationsentinel.model.enums.ReviewMode;
import com.migrationsentinel.payload.dto.MigrationFile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Submit one candidate migration for review, against the project's migration history")
public record CreateReviewRequest(

        @Schema(example = "V42__add_customer_tier.sql")
        @Size(max = 255)
        String filename,

        @NotBlank
        @Size(max = 2_000_000)
        @Schema(description = "The candidate Flyway migration SQL", requiredMode = Schema.RequiredMode.REQUIRED)
        String migrationSql,

        @Valid
        @Size(max = 5_000, message = "at most 5000 prior migration files")
        @Schema(description = "The project's prior migrations, one entry per file. Replayed into the sandbox in "
                + "Flyway version order (V1, V2, … V10 — not string order) before the candidate runs. Prefer this "
                + "over baseline_sql: it is ordered for you and a replay failure names the file it came from.")
        List<MigrationFile> baselineMigrations,

        @Size(max = 20_000_000)
        @Schema(description = "Prior migrations as one pre-concatenated script. Legacy alternative to "
                + "baseline_migrations; ignored when baseline_migrations is present.")
        String baselineSql,

        @Pattern(regexp = "^$|^[A-Za-z_][A-Za-z0-9_$]{0,62}$", message = "must be a plain schema identifier")
        @Schema(description = "The schema the migrations build into — the project's Flyway "
                + "`spring.flyway.schemas`. Created in the sandbox and put on the search path before "
                + "the replay. Defaults to public.", example = "identity")
        String targetSchema,

        @Size(max = 2_000_000)
        @Schema(description = "Seed rows and/or planner-stat setup applied after the baseline "
                + "(e.g. INSERT ..., or UPDATE pg_class SET reltuples=5e7 ...; ANALYZE;)")
        String seedSql,

        @Size(max = 500_000)
        @Schema(description = "JPA entity source (Java) or a JSON mapping spec, for the entity/schema drift check")
        String entitySource,

        @Schema(description = "Which point on the improvement curve to run", defaultValue = "ANALYZER_VERIFIER_SPLIT")
        ReviewMode mode,

        @Schema(description = "LLM provider: heuristic (offline default), openai, or gemini", defaultValue = "heuristic")
        String provider,

        @Size(max = 500)
        @Schema(description = "Optional: your own API key for this one review, used instead of any "
                + "server-configured key. Encrypted at rest, never returned, stripped from logs.")
        String llmApiKey
) {

    /** Total SQL bytes a whole history may carry. A 440-file service sits near 3 MB. */
    public static final long MAX_HISTORY_CHARS = 20_000_000L;

    public ReviewMode modeOrDefault() {
        return mode == null ? ReviewMode.ANALYZER_VERIFIER_SPLIT : mode;
    }

    public boolean hasBaselineFiles() {
        return baselineMigrations != null && !baselineMigrations.isEmpty();
    }
}
