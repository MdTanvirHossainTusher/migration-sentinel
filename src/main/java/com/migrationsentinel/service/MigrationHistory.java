package com.migrationsentinel.service;

import com.migrationsentinel.payload.dto.MigrationFile;
import com.migrationsentinel.util.FlywayVersion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns a project's migration folder into the ordered baseline the sandbox replays.
 *
 * <p>The reviewer is only as good as the schema it reviews against. Reviewing a candidate
 * against one hand-picked predecessor answers a different question than the one the engineer
 * is asking: the candidate runs on production, and production is <em>every</em> migration
 * that came before it. So the baseline is the whole ordered history, and the ordering is
 * Flyway's — see {@link FlywayVersion}.
 */
public final class MigrationHistory {

    /** Marker written between files so a replay failure can be attributed back to a filename. */
    public static final String FILE_MARKER_PREFIX = "-- >>> migration-sentinel:file ";

    private MigrationHistory() {
    }

    /**
     * Sort by Flyway version and drop anything with no SQL in it. Undo migrations are
     * excluded — Flyway never applies them on the way forward, so replaying one would build
     * a schema production has never had.
     */
    public static List<MigrationFile> ordered(List<MigrationFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<MigrationFile> kept = new ArrayList<>();
        for (MigrationFile f : files) {
            if (f != null && f.hasSql() && f.version().kind() != FlywayVersion.Kind.UNDO) {
                kept.add(f);
            }
        }
        // Stable sort: files the parser cannot version keep the order the caller sent them in.
        kept.sort(Comparator.comparing(MigrationFile::version));
        return List.copyOf(kept);
    }

    /**
     * Flatten an ordered history into one script, each file preceded by a marker comment.
     * The marker is a SQL line comment, so it is inert to Postgres and survives the splitter.
     */
    public static String concat(List<MigrationFile> ordered) {
        if (ordered == null || ordered.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (MigrationFile f : ordered) {
            sb.append(FILE_MARKER_PREFIX).append(f.filename()).append('\n');
            sb.append(f.sql().strip()).append('\n');
            if (!f.sql().strip().endsWith(";")) {
                sb.append(";\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * The inverse of {@link #concat}: recover the per-file split from a flattened baseline.
     * Only the flattened script is persisted (storing both would double the SQL on disk), so
     * this is what gives the replayer back the filenames it needs for failure attribution.
     * A script written without markers — a hand-pasted baseline — comes back as one file.
     */
    public static List<MigrationFile> split(String flattened) {
        if (flattened == null || flattened.isBlank()) {
            return List.of();
        }
        if (!flattened.contains(FILE_MARKER_PREFIX)) {
            return List.of(new MigrationFile("baseline.sql", flattened));
        }
        List<MigrationFile> out = new ArrayList<>();
        String filename = null;
        StringBuilder body = new StringBuilder();
        for (String line : flattened.split("\n", -1)) {
            if (line.startsWith(FILE_MARKER_PREFIX)) {
                if (filename != null && !body.toString().isBlank()) {
                    out.add(new MigrationFile(filename, body.toString()));
                }
                filename = line.substring(FILE_MARKER_PREFIX.length()).trim();
                body.setLength(0);
                continue;
            }
            body.append(line).append('\n');
        }
        if (filename != null && !body.toString().isBlank()) {
            out.add(new MigrationFile(filename, body.toString()));
        }
        return List.copyOf(out);
    }

    /** Total SQL characters across the history — used to reject a payload before it is stored. */
    public static long totalLength(List<MigrationFile> files) {
        if (files == null) {
            return 0;
        }
        long total = 0;
        for (MigrationFile f : files) {
            if (f != null) {
                total += f.length();
            }
        }
        return total;
    }

    /** A short summary for the UI and the report, e.g. {@code "440 migrations, V1 → V440"}. */
    public static String describe(List<MigrationFile> ordered) {
        if (ordered == null || ordered.isEmpty()) {
            return "no prior migrations";
        }
        List<MigrationFile> versioned = ordered.stream().filter(f -> f.version().isVersioned()).toList();
        String range = versioned.isEmpty()
                ? ""
                : ", " + versioned.get(0).version().label() + " → "
                        + versioned.get(versioned.size() - 1).version().label();
        return ordered.size() + " migration" + (ordered.size() == 1 ? "" : "s") + range;
    }
}
