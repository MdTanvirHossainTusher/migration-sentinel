package com.migrationsentinel.service.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.migrationsentinel.payload.dto.SchemaDriftReport;
import com.migrationsentinel.payload.dto.TableStat;
import com.migrationsentinel.service.support.AgentJsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Hibernate-{@code validate}-equivalent check without needing the entity classes on the
 * classpath. It reads the supplied JPA mapping — either Java entity source (parsed for
 * {@code @Table}/{@code @Column}/{@code @Id}/{@code @JoinColumn}) or a small JSON spec —
 * and checks every mapped column against the live post-migration sandbox schema:
 * column presence, nullability, and broad type family. This is exactly the class of
 * failure that takes an app down at boot right after a migration commits.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpaMappingValidator {

    private final AgentJsonMapper objectMapper;

    private static final Pattern TABLE = Pattern.compile("@Table\\s*\\(\\s*name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ENTITY_CLASS = Pattern.compile("class\\s+([A-Za-z0-9_]+)");
    private static final Pattern FIELD_BLOCK = Pattern.compile(
            "((?:@[A-Za-z]+(?:\\s*\\([^)]*\\))?\\s*)+)\\s+(?:private|protected|public)\\s+([A-Za-z0-9_<>.]+)\\s+([A-Za-z0-9_]+)\\s*;");
    private static final Pattern COLUMN_NAME = Pattern.compile("@(?:Column|JoinColumn)\\s*\\([^)]*name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern NULLABLE_FALSE = Pattern.compile("nullable\\s*=\\s*false");

    public SchemaDriftReport validate(String entitySource, Map<String, TableStat> schema) {
        if (entitySource == null || entitySource.isBlank()) {
            return SchemaDriftReport.notRun("no entity source supplied");
        }
        try {
            String trimmed = entitySource.trim();
            List<MappedColumn> mapped = trimmed.startsWith("{") || trimmed.startsWith("[")
                    ? parseJson(trimmed)
                    : parseJava(trimmed);
            if (mapped.isEmpty()) {
                return SchemaDriftReport.notRun("could not extract any mapped columns from the entity source");
            }
            List<SchemaDriftReport.DriftItem> items = new ArrayList<>();
            for (MappedColumn mc : mapped) {
                TableStat table = schema.get(mc.table);
                if (table == null) {
                    items.add(new SchemaDriftReport.DriftItem(mc.entity,
                            "maps to table '" + mc.table + "' which does not exist after the migration"));
                    continue;
                }
                Optional<TableStat.ColumnInfo> col = table.columns().stream()
                        .filter(c -> c.name().equalsIgnoreCase(mc.column)).findFirst();
                if (col.isEmpty()) {
                    items.add(new SchemaDriftReport.DriftItem(mc.entity,
                            "field '" + mc.field + "' maps to column '" + mc.table + "." + mc.column
                                    + "' which does not exist after the migration"));
                    continue;
                }
                if (mc.notNull && col.get().nullable()) {
                    items.add(new SchemaDriftReport.DriftItem(mc.entity,
                            "field '" + mc.field + "' is nullable=false but column '" + mc.table + "." + mc.column
                                    + "' is NULLABLE in the schema"));
                }
                String family = typeFamily(mc.javaType);
                String colFamily = pgTypeFamily(col.get().type());
                if (family != null && colFamily != null && !family.equals(colFamily)) {
                    items.add(new SchemaDriftReport.DriftItem(mc.entity,
                            "field '" + mc.field + "' (" + mc.javaType + ", " + family + ") maps to column '"
                                    + mc.column + "' of type " + col.get().type() + " (" + colFamily + ")"));
                }
            }
            return new SchemaDriftReport(true, items.isEmpty(), items,
                    items.isEmpty() ? "entity mapping is consistent with the post-migration schema"
                            : items.size() + " mapping mismatch(es)");
        } catch (Exception ex) {
            log.warn("JPA mapping validation failed: {}", ex.getMessage());
            return SchemaDriftReport.notRun("validation error: " + ex.getMessage());
        }
    }

    private List<MappedColumn> parseJava(String source) {
        List<MappedColumn> out = new ArrayList<>();
        Matcher tm = TABLE.matcher(source);
        String table = tm.find() ? tm.group(1).toLowerCase(Locale.ROOT) : null;
        Matcher cm = ENTITY_CLASS.matcher(source);
        String entity = cm.find() ? cm.group(1) : "Entity";
        if (table == null) {
            table = camelToSnake(entity);
        }
        Matcher fb = FIELD_BLOCK.matcher(source);
        while (fb.find()) {
            String annotations = fb.group(1);
            String javaType = fb.group(2);
            String field = fb.group(3);
            if (!annotations.contains("@Column") && !annotations.contains("@JoinColumn") && !annotations.contains("@Id")) {
                continue;
            }
            Matcher col = COLUMN_NAME.matcher(annotations);
            String column = col.find() ? col.group(1) : camelToSnake(field);
            boolean notNull = NULLABLE_FALSE.matcher(annotations).find() || annotations.contains("@Id");
            out.add(new MappedColumn(entity, table, field, column.toLowerCase(Locale.ROOT), javaType, notNull));
        }
        return out;
    }

    private List<MappedColumn> parseJson(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        List<MappedColumn> out = new ArrayList<>();
        JsonNode entities = root.isArray() ? root : objectMapper.createArrayNode().add(root);
        for (JsonNode e : entities) {
            String entity = e.path("entity").asText("Entity");
            String table = e.path("table").asText().toLowerCase(Locale.ROOT);
            for (JsonNode f : e.path("columns")) {
                out.add(new MappedColumn(entity, table,
                        f.path("field").asText(f.path("name").asText()),
                        f.path("name").asText().toLowerCase(Locale.ROOT),
                        f.path("type").asText("String"),
                        !f.path("nullable").asBoolean(true)));
            }
        }
        return out;
    }

    private String typeFamily(String javaType) {
        String t = javaType.toLowerCase(Locale.ROOT);
        if (t.contains("string") || t.contains("char")) {
            return "text";
        }
        if (t.contains("int") || t.contains("long") || t.contains("short") || t.contains("bigdecimal")
                || t.contains("double") || t.contains("float")) {
            return "number";
        }
        if (t.contains("bool")) {
            return "boolean";
        }
        if (t.contains("instant") || t.contains("localdate") || t.contains("offsetdatetime")
                || t.contains("timestamp") || t.contains("zoneddatetime")) {
            return "temporal";
        }
        if (t.contains("uuid")) {
            return "uuid";
        }
        return null;
    }

    private String pgTypeFamily(String pgType) {
        String t = pgType.toLowerCase(Locale.ROOT);
        if (t.contains("char") || t.equals("text")) {
            return "text";
        }
        if (t.contains("int") || t.contains("numeric") || t.contains("decimal") || t.contains("double")
                || t.contains("real") || t.contains("serial")) {
            return "number";
        }
        if (t.contains("bool")) {
            return "boolean";
        }
        if (t.contains("timestamp") || t.contains("date") || t.contains("time")) {
            return "temporal";
        }
        if (t.contains("uuid")) {
            return "uuid";
        }
        return null;
    }

    private String camelToSnake(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private record MappedColumn(String entity, String table, String field, String column, String javaType,
                                boolean notNull) {
    }
}
