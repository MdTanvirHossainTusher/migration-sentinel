package com.migrationsentinel.payload.dto;

import com.migrationsentinel.model.enums.ReviewMode;

import java.util.List;

/**
 * Everything the pipeline needs to review one candidate migration. Assembled by
 * {@code ReviewService} from either an API request or an evaluation case on disk.
 *
 * <p>{@code baselineSql} is the whole prior history flattened into one script;
 * {@code baselineFiles} is that same history still split by file, which is what lets a
 * replay failure name the migration that caused it. Evaluation cases supply only the
 * flattened form, so {@code baselineFiles} may be empty.
 */
public record MigrationInput(
        String filename,
        String migrationSql,
        String baselineSql,
        List<MigrationFile> baselineFiles,
        String targetSchema,
        String seedSql,
        String entitySource,
        ReviewMode mode,
        String provider,
        String caseId,
        /** Decrypted per-request API key, or null. Never persisted from here, never logged. */
        String llmApiKey
) {
    /** Convenience for evaluation cases and tests, which carry a flattened baseline only. */
    public MigrationInput(String filename, String migrationSql, String baselineSql, String seedSql,
                          String entitySource, ReviewMode mode, String provider, String caseId) {
        this(filename, migrationSql, baselineSql, List.of(), null, seedSql, entitySource, mode, provider, caseId, null);
    }

    public boolean hasBaseline() {
        return baselineSql != null && !baselineSql.isBlank();
    }

    public List<MigrationFile> baselineFilesOrEmpty() {
        return baselineFiles == null ? List.of() : baselineFiles;
    }

    public boolean hasSeed() {
        return seedSql != null && !seedSql.isBlank();
    }

    public boolean hasEntitySource() {
        return entitySource != null && !entitySource.isBlank();
    }
}
