package com.migrationsentinel.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The version stamped on a Flyway migration filename, ordered the way Flyway orders it.
 *
 * <p>This exists because sorting migration filenames as strings is wrong the moment a
 * project passes ten migrations: {@code V10__x.sql} sorts before {@code V2__x.sql}
 * lexicographically, so replaying a real project's history in string order rebuilds a
 * schema that never existed. Versions are compared part by part as numbers.
 *
 * <p>Recognised prefixes: {@code V} (versioned), {@code R} (repeatable — no version, always
 * applied last), {@code U} (undo — never part of a baseline). Separators {@code .} and
 * {@code _} are equivalent, matching Flyway.
 */
public record FlywayVersion(Kind kind, List<Long> parts, String description, String filename)
        implements Comparable<FlywayVersion> {

    public enum Kind {
        VERSIONED,
        REPEATABLE,
        UNDO,
        /** Not a Flyway migration filename at all. */
        UNKNOWN
    }

    private static final Pattern PATTERN = Pattern.compile(
            "^(?<prefix>[VRU])(?<version>[0-9]+(?:[._][0-9]+)*)?(?:__(?<description>.*?))?(?:\\.sql)?$",
            Pattern.CASE_INSENSITIVE);

    public static FlywayVersion parse(String filename) {
        String name = stripPath(filename);
        String bare = name.toLowerCase(Locale.ROOT).endsWith(".sql")
                ? name.substring(0, name.length() - 4)
                : name;

        Matcher m = PATTERN.matcher(bare);
        if (!m.matches()) {
            return new FlywayVersion(Kind.UNKNOWN, List.of(), bare, name);
        }

        String prefix = m.group("prefix").toUpperCase(Locale.ROOT);
        String version = m.group("version");
        String description = m.group("description") == null ? "" : m.group("description");

        Kind kind = switch (prefix) {
            case "R" -> Kind.REPEATABLE;
            case "U" -> Kind.UNDO;
            default -> Kind.VERSIONED;
        };
        // Flyway's shapes: V and U carry a version, R never does. Anything else is not a
        // migration filename and is treated as unrecognised rather than mis-ordered.
        boolean hasVersion = version != null && !version.isBlank();
        if (kind == Kind.REPEATABLE ? hasVersion : !hasVersion) {
            return new FlywayVersion(Kind.UNKNOWN, List.of(), bare, name);
        }
        return new FlywayVersion(kind, numericParts(version), description, name);
    }

    public boolean isVersioned() {
        return kind == Kind.VERSIONED;
    }

    /** Human-readable version, e.g. {@code V182} or {@code V1.3}; empty for repeatable migrations. */
    public String label() {
        return switch (kind) {
            case VERSIONED -> "V" + String.join(".", parts.stream().map(String::valueOf).toList());
            case REPEATABLE -> "R";
            case UNDO -> "U";
            case UNKNOWN -> "";
        };
    }

    /**
     * Flyway's own ordering: versioned migrations first in numeric version order, then
     * repeatable migrations by description. Anything unrecognised keeps its input order at
     * the end, so a hand-written file is still replayed rather than silently dropped.
     */
    @Override
    public int compareTo(FlywayVersion other) {
        int byKind = Integer.compare(rank(), other.rank());
        if (byKind != 0) {
            return byKind;
        }
        if (kind == Kind.VERSIONED) {
            int size = Math.max(parts.size(), other.parts.size());
            for (int i = 0; i < size; i++) {
                int cmp = Long.compare(partAt(i), other.partAt(i));
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        }
        return description.compareToIgnoreCase(other.description);
    }

    private int rank() {
        return switch (kind) {
            case VERSIONED -> 0;
            case REPEATABLE -> 1;
            case UNDO -> 2;
            case UNKNOWN -> 3;
        };
    }

    private long partAt(int i) {
        return i < parts.size() ? parts.get(i) : 0L;
    }

    private static List<Long> numericParts(String version) {
        if (version == null || version.isBlank()) {
            return List.of();
        }
        List<Long> out = new ArrayList<>();
        for (String part : version.split("[._]")) {
            try {
                out.add(Long.parseLong(part));
            } catch (NumberFormatException ignored) {
                out.add(0L);
            }
        }
        return List.copyOf(out);
    }

    private static String stripPath(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        String normalized = filename.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }
}
