package com.migrationsentinel.payload.dto;

import java.util.List;

/** Output of the Hibernate {@code validate} pass against the post-migration sandbox schema. */
public record SchemaDriftReport(
        boolean ran,
        boolean consistent,
        List<DriftItem> items,
        String rawMessage
) {
    public record DriftItem(String entity, String detail) {
    }

    public static SchemaDriftReport notRun(String reason) {
        return new SchemaDriftReport(false, true, List.of(), reason);
    }
}
