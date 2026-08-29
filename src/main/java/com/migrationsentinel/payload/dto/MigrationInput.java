package com.migrationsentinel.payload.dto;

import com.migrationsentinel.model.enums.ReviewMode;

/**
 * Everything the pipeline needs to review one candidate migration. Assembled by
 * {@code ReviewService} from either an API request or an evaluation case on disk.
 */
public record MigrationInput(
        String filename,
        String migrationSql,
        String baselineSql,
        String seedSql,
        String entitySource,
        ReviewMode mode,
        String provider,
        String caseId
) {
    public boolean hasBaseline() {
        return baselineSql != null && !baselineSql.isBlank();
    }

    public boolean hasSeed() {
        return seedSql != null && !seedSql.isBlank();
    }

    public boolean hasEntitySource() {
        return entitySource != null && !entitySource.isBlank();
    }
}
