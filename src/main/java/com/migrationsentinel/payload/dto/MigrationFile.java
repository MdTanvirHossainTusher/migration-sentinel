package com.migrationsentinel.payload.dto;

import com.migrationsentinel.util.FlywayVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One migration file from the project's history, kept as a file rather than folded into a
 * single blob so the sandbox can report <em>which</em> migration failed to replay. Replaying
 * a 400-file history and getting back one line of Postgres error text is not a diagnosis.
 */
@Schema(description = "One prior migration file, replayed into the sandbox before the candidate")
public record MigrationFile(

        @NotBlank
        @Size(max = 255)
        @Schema(example = "V182__farmer_report_dashboard.sql", requiredMode = Schema.RequiredMode.REQUIRED)
        String filename,

        @Size(max = 2_000_000)
        @Schema(description = "The file's SQL body", requiredMode = Schema.RequiredMode.REQUIRED)
        String sql
) {

    public FlywayVersion version() {
        return FlywayVersion.parse(filename);
    }

    public boolean hasSql() {
        return sql != null && !sql.isBlank();
    }

    public int length() {
        return sql == null ? 0 : sql.length();
    }
}
