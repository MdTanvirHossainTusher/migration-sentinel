package com.migrationsentinel.service.rules;

import com.migrationsentinel.payload.dto.SandboxRunResult;
import com.migrationsentinel.payload.dto.TableStat;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A thin lookup over whatever the sandbox managed to observe. When the sandbox did not
 * run (BASELINE_PROMPT mode, or no Docker) every accessor returns "unknown" and the
 * scanner degrades to structure-only rules.
 */
public record SchemaFacts(List<TableStat> tables, long largeTableThreshold, boolean sandboxRan) {

    public static SchemaFacts empty(long threshold) {
        return new SchemaFacts(List.of(), threshold, false);
    }

    public static SchemaFacts from(SandboxRunResult run, long threshold) {
        return new SchemaFacts(run.tableStatsAfter(), threshold, run.candidateApplied() || run.baselineApplied());
    }

    public Optional<TableStat> table(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String target = name.toLowerCase(Locale.ROOT);
        return tables.stream().filter(t -> t.table().equalsIgnoreCase(target)).findFirst();
    }

    /** Best available row estimate for a table, or -1 when the sandbox could not measure it. */
    public long rowEstimate(String table) {
        return table(table).map(t -> t.exactRows() != null ? t.exactRows() : t.estimatedRows()).orElse(-1L);
    }

    public boolean isLarge(String table) {
        long rows = rowEstimate(table);
        return rows >= 0 && rows >= largeTableThreshold;
    }

    public boolean columnCoveredByIndex(String table, String column) {
        return table(table).map(t -> t.indexes().stream()
                .anyMatch(ix -> !ix.columns().isEmpty()
                        && ix.columns().get(0).equalsIgnoreCase(column))).orElse(false);
    }
}
