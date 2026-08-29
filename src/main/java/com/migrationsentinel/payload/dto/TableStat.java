package com.migrationsentinel.payload.dto;

import java.util.List;

/** Introspected shape and size of one table in the sandbox. */
public record TableStat(
        String table,
        long estimatedRows,
        Long exactRows,
        long sizeBytes,
        List<ColumnInfo> columns,
        List<IndexInfo> indexes,
        List<ForeignKeyInfo> foreignKeys
) {
    public record ColumnInfo(String name, String type, boolean nullable, String defaultExpr) {
    }

    public record IndexInfo(String name, List<String> columns, boolean unique, boolean primary) {
    }

    public record ForeignKeyInfo(String name, List<String> columns, String referencedTable, boolean covered) {
    }
}
