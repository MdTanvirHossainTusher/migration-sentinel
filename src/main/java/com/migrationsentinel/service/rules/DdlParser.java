package com.migrationsentinel.service.rules;

import com.migrationsentinel.util.SqlScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies raw migration statements into {@link ParsedStatement}s. Regex-driven and
 * deliberately conservative: anything it cannot classify becomes {@code OTHER} and is
 * left for the LLM analyzer to reason about.
 */
@Component
public class DdlParser {

    private static final Pattern IDENT = Pattern.compile("(?:\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_$]*))(?:\\.(?:\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_$]*)))?");

    public List<ParsedStatement> parse(String script) {
        List<ParsedStatement> out = new ArrayList<>();
        List<String> statements = SqlScript.split(script);
        for (int i = 0; i < statements.size(); i++) {
            out.add(classify(i, statements.get(i)));
        }
        return out;
    }

    ParsedStatement classify(int index, String raw) {
        String s = SqlScript.stripComments(raw).trim();
        String norm = s.replaceAll("\\s+", " ").trim();
        String lower = norm.toLowerCase(Locale.ROOT);
        boolean concurrently = lower.contains(" concurrently");
        boolean notValid = lower.contains("not valid");

        if (lower.startsWith("drop table")) {
            return simple(index, raw, norm, ParsedStatement.Kind.DROP_TABLE, tableAfter(norm, "drop table"));
        }
        if (lower.startsWith("truncate")) {
            return simple(index, raw, norm, ParsedStatement.Kind.TRUNCATE, tableAfter(norm, "truncate table", "truncate"));
        }
        if (lower.startsWith("create table")) {
            return simple(index, raw, norm, ParsedStatement.Kind.CREATE_TABLE, tableAfter(norm, "create table if not exists", "create table"));
        }
        if (lower.startsWith("drop index")) {
            return new ParsedStatement(index, raw, norm, ParsedStatement.Kind.DROP_INDEX, null,
                    List.of(), concurrently, null, null, null, notValid);
        }
        if (lower.startsWith("create") && lower.contains("index")) {
            return parseCreateIndex(index, raw, norm, lower, concurrently);
        }
        if (lower.startsWith("alter type") && lower.contains("add value")) {
            return simple(index, raw, norm, ParsedStatement.Kind.ALTER_TYPE_ADD_VALUE, tableAfter(norm, "alter type"));
        }
        if (lower.startsWith("alter table")) {
            return parseAlterTable(index, raw, norm, lower, concurrently, notValid);
        }
        return new ParsedStatement(index, raw, norm, ParsedStatement.Kind.OTHER, null,
                List.of(), concurrently, null, null, null, notValid);
    }

    private ParsedStatement parseCreateIndex(int index, String raw, String norm, String lower, boolean concurrently) {
        Matcher m = Pattern.compile("on\\s+(?:only\\s+)?([A-Za-z0-9_.\"]+)\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE)
                .matcher(norm);
        String table = null;
        List<String> cols = new ArrayList<>();
        if (m.find()) {
            table = unquote(lastPart(m.group(1)));
            for (String c : m.group(2).split(",")) {
                String col = c.trim().replaceAll("\\s+(asc|desc)$", "").trim();
                col = col.replaceAll("^\"|\"$", "");
                if (!col.isEmpty()) {
                    cols.add(col.toLowerCase(Locale.ROOT));
                }
            }
        }
        return new ParsedStatement(index, raw, norm, ParsedStatement.Kind.CREATE_INDEX, table,
                cols, concurrently, null, null, null, false);
    }

    private ParsedStatement parseAlterTable(int index, String raw, String norm, String lower,
                                            boolean concurrently, boolean notValid) {
        String table = tableAfter(norm, "alter table if exists", "alter table only", "alter table");

        if (lower.matches(".*\\badd\\s+column\\b.*") || lower.matches("alter table \\S+ add [A-Za-z_\"].*(?<!constraint).*")) {
            if (lower.contains(" add constraint") || lower.contains(" add primary key") || lower.contains(" add foreign key")
                    || lower.contains(" add unique") || lower.contains(" add check")) {
                // fall through to constraint handling below
            } else {
                String col = firstColumnAfterAdd(norm);
                String def = defaultExpr(norm);
                return new ParsedStatement(index, raw, norm, ParsedStatement.Kind.ADD_COLUMN, table,
                        col == null ? List.of() : List.of(col.toLowerCase(Locale.ROOT)), concurrently, def, null, null, notValid);
            }
        }
        if (lower.contains(" drop column")) {
            String col = wordAfter(norm, "drop column");
            if (col == null) {
                col = wordAfter(norm, "drop");
            }
            return new ParsedStatement(index, raw, norm, ParsedStatement.Kind.DROP_COLUMN, table,
                    col == null ? List.of() : List.of(col.toLowerCase(Locale.ROOT)), concurrently, null, null, null, notValid);
        }
        if (lower.contains("set not null")) {
            String col = columnInAlterColumn(norm);
            return new ParsedStatement(index, raw, norm, ParsedStatement.Kind.SET_NOT_NULL, table,
                    col == null ? List.of() : List.of(col.toLowerCase(Locale.ROOT)), concurrently, null, null, null, notValid);
        }
        if (lower.contains("drop not null")) {
            String col = columnInAlterColumn(norm);
            return new ParsedStatement(index, raw, norm, ParsedStatement.Kind.DROP_NOT_NULL, table,
                    col == null ? List.of() : List.of(col.toLowerCase(Locale.ROOT)), concurrently, null, null, null, notValid);
        }
        if (lower.matches(".*\\b(set data type|type)\\b.*") && lower.contains("alter column")) {
            String col = columnInAlterColumn(norm);
            String newType = typeAfter(norm);
            return new ParsedStatement(index, raw, norm, ParsedStatement.Kind.ALTER_COLUMN_TYPE, table,
                    col == null ? List.of() : List.of(col.toLowerCase(Locale.ROOT)), concurrently, null, newType, null, notValid);
        }
        if (lower.contains("rename column")) {
            String col = wordAfter(norm, "rename column");
            return new ParsedStatement(index, raw, norm, ParsedStatement.Kind.RENAME_COLUMN, table,
                    col == null ? List.of() : List.of(col.toLowerCase(Locale.ROOT)), concurrently, null, null, null, notValid);
        }
        if (lower.contains("rename to")) {
            return new ParsedStatement(index, raw, norm, ParsedStatement.Kind.RENAME_TABLE, table,
                    List.of(), concurrently, null, null, null, notValid);
        }
        if (lower.contains("validate constraint")) {
            return simple(index, raw, norm, ParsedStatement.Kind.VALIDATE_CONSTRAINT, table);
        }
        if ((lower.contains("add constraint") && lower.contains("foreign key")) || lower.contains("add foreign key")) {
            String ref = tableAfter(norm.replaceAll("(?i).*references", "references"), "references");
            List<String> cols = columnsInParensAfter(norm, "foreign key");
            return new ParsedStatement(index, raw, norm, ParsedStatement.Kind.ADD_FOREIGN_KEY, table,
                    cols, concurrently, null, null, ref, notValid);
        }
        if ((lower.contains("add constraint") && lower.contains("check")) || lower.contains("add check")) {
            return new ParsedStatement(index, raw, norm, ParsedStatement.Kind.ADD_CHECK_CONSTRAINT, table,
                    List.of(), concurrently, null, null, null, notValid);
        }
        if ((lower.contains("add constraint") && lower.contains("unique")) || lower.contains("add unique")) {
            return new ParsedStatement(index, raw, norm, ParsedStatement.Kind.ADD_UNIQUE_CONSTRAINT, table,
                    columnsInParensAfter(norm, "unique"), concurrently, null, null, null, notValid);
        }
        return new ParsedStatement(index, raw, norm, ParsedStatement.Kind.OTHER, table,
                List.of(), concurrently, null, null, null, notValid);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ParsedStatement simple(int index, String raw, String norm, ParsedStatement.Kind kind, String table) {
        return new ParsedStatement(index, raw, norm, kind, table, List.of(), false, null, null, null, false);
    }

    private String tableAfter(String norm, String... prefixes) {
        String lower = norm.toLowerCase(Locale.ROOT);
        for (String p : prefixes) {
            int idx = lower.indexOf(p);
            if (idx >= 0) {
                String rest = norm.substring(idx + p.length()).trim();
                Matcher m = IDENT.matcher(rest);
                if (m.lookingAt()) {
                    return unquote(lastPart(m.group()));
                }
            }
        }
        return null;
    }

    private String wordAfter(String norm, String keyword) {
        String lower = norm.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf(keyword);
        if (idx < 0) {
            return null;
        }
        String rest = norm.substring(idx + keyword.length()).trim();
        Matcher m = IDENT.matcher(rest);
        if (m.lookingAt()) {
            return unquote(m.group().replaceAll("[,;].*$", "").trim());
        }
        return null;
    }

    private String firstColumnAfterAdd(String norm) {
        Matcher m = Pattern.compile("(?i)\\badd\\s+(?:column\\s+)?(?:if\\s+not\\s+exists\\s+)?(\"?[A-Za-z_][A-Za-z0-9_$]*\"?)")
                .matcher(norm);
        return m.find() ? unquote(m.group(1)) : null;
    }

    private String columnInAlterColumn(String norm) {
        Matcher m = Pattern.compile("(?i)alter\\s+column\\s+(\"?[A-Za-z_][A-Za-z0-9_$]*\"?)").matcher(norm);
        if (m.find()) {
            return unquote(m.group(1));
        }
        m = Pattern.compile("(?i)alter\\s+(\"?[A-Za-z_][A-Za-z0-9_$]*\"?)\\s+(set|type|drop)").matcher(norm);
        return m.find() ? unquote(m.group(1)) : null;
    }

    private String typeAfter(String norm) {
        Matcher m = Pattern.compile("(?i)(?:set\\s+data\\s+type|type)\\s+([A-Za-z0-9_ ()\\[\\]]+?)(?:\\s+using|;|$)").matcher(norm);
        return m.find() ? m.group(1).trim() : null;
    }

    private String defaultExpr(String norm) {
        Matcher m = Pattern.compile("(?i)\\bdefault\\s+(.+?)(?:\\s+not\\s+null|\\s+null|,|;|$)").matcher(norm);
        return m.find() ? m.group(1).trim() : null;
    }

    private List<String> columnsInParensAfter(String norm, String keyword) {
        Matcher m = Pattern.compile("(?i)" + Pattern.quote(keyword) + "\\s*\\(([^)]*)\\)").matcher(norm);
        List<String> cols = new ArrayList<>();
        if (m.find()) {
            for (String c : m.group(1).split(",")) {
                String col = unquote(c.trim());
                if (!col.isEmpty()) {
                    cols.add(col.toLowerCase(Locale.ROOT));
                }
            }
        }
        return cols;
    }

    private String lastPart(String qualified) {
        String[] parts = qualified.split("\\.");
        return parts[parts.length - 1];
    }

    private String unquote(String v) {
        if (v == null) {
            return null;
        }
        return v.replaceAll("^\"|\"$", "").trim().toLowerCase(Locale.ROOT);
    }
}
