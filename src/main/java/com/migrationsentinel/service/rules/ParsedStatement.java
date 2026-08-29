package com.migrationsentinel.service.rules;

import java.util.List;

/** A single migration statement classified into the shape the rule scanner reasons over. */
public record ParsedStatement(
        int index,
        String raw,
        String normalized,
        Kind kind,
        String table,
        List<String> columns,
        boolean concurrently,
        String defaultExpr,
        String newType,
        String referencedTable,
        boolean notValid
) {
    public enum Kind {
        CREATE_TABLE,
        DROP_TABLE,
        TRUNCATE,
        ADD_COLUMN,
        DROP_COLUMN,
        SET_NOT_NULL,
        DROP_NOT_NULL,
        ALTER_COLUMN_TYPE,
        CREATE_INDEX,
        DROP_INDEX,
        ADD_FOREIGN_KEY,
        ADD_CHECK_CONSTRAINT,
        ADD_UNIQUE_CONSTRAINT,
        VALIDATE_CONSTRAINT,
        RENAME_COLUMN,
        RENAME_TABLE,
        ALTER_TYPE_ADD_VALUE,
        OTHER
    }
}
