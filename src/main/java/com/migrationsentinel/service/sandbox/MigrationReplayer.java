package com.migrationsentinel.service.sandbox;

import com.migrationsentinel.config.properties.SandboxProperties;
import com.migrationsentinel.payload.dto.LockObservation;
import com.migrationsentinel.payload.dto.MigrationFile;
import com.migrationsentinel.payload.dto.SandboxRunResult;
import com.migrationsentinel.payload.dto.SandboxRunResult.BaselineReplay;
import com.migrationsentinel.payload.dto.StatementOutcome;
import com.migrationsentinel.payload.dto.TableStat;
import com.migrationsentinel.service.rules.DdlParser;
import com.migrationsentinel.service.rules.ParsedStatement;
import com.migrationsentinel.util.SqlScript;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Replays the baseline migrations and optional seed, then runs the candidate migration
 * statement by statement against the sandbox — timing each one, capturing errors, and
 * (best effort) catching the locks it holds. This is the reviewer's strongest evidence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationReplayer {

    private final SandboxProperties properties;
    private final SchemaIntrospector introspector;
    private final LockAnalyzer lockAnalyzer;
    private final DdlParser ddlParser;

    /** Apply the baseline + seed only, leaving the schema un-migrated. Used by the read-only ablation mode. */
    public SandboxRunResult replayBaselineOnly(SandboxSession session, String baselineSql, String seedSql) {
        return replayBaselineOnly(session, List.of(), baselineSql, seedSql, null);
    }

    public SandboxRunResult replayBaselineOnly(SandboxSession session, List<MigrationFile> baselineFiles,
                                               String baselineSql, String seedSql, String targetSchema) {
        try (Connection worker = session.open()) {
            worker.setAutoCommit(true);
            prepareSchema(worker, targetSchema);
            BaselineReplay baseline = applyBaseline(worker, baselineFiles, baselineSql);
            if (baseline.failed()) {
                return new SandboxRunResult(false, false, 0, baseline.describeFailure(),
                        List.of(), List.of(), List.of(), false, false, baseline);
            }
            widenSearchPath(worker);
            if (seedSql != null && !seedSql.isBlank()) {
                execAll(worker, SqlScript.split(seedSql));
            }
            Map<String, TableStat> snapshot = snapshotMap(worker);
            List<TableStat> stats = new ArrayList<>(snapshot.values());
            return new SandboxRunResult(true, false, 0, null, List.of(), List.of(), stats, false,
                    true, baseline);
        } catch (SQLException ex) {
            return SandboxRunResult.unavailable("sandbox setup failed: " + ex.getMessage());
        }
    }

    public SandboxRunResult replay(SandboxSession session, String baselineSql, String seedSql, String candidateSql) {
        return replay(session, List.of(), baselineSql, seedSql, candidateSql, null);
    }

    public SandboxRunResult replay(SandboxSession session, List<MigrationFile> baselineFiles, String baselineSql,
                                   String seedSql, String candidateSql, String targetSchema) {
        List<StatementOutcome> outcomes = new ArrayList<>();
        List<LockObservation> locks = new ArrayList<>();
        boolean baselineApplied = false;
        BaselineReplay baseline = BaselineReplay.none();

        try (Connection worker = session.open()) {
            worker.setAutoCommit(true);

            // 1. Baseline — the project's prior migration history, in Flyway version order. A
            //    failure here is a setup problem, not a finding, but it must be reported: with
            //    the baseline half-applied the candidate is being reviewed against a schema
            //    that does not exist anywhere, so every measurement below it is meaningless.
            prepareSchema(worker, targetSchema);
            baseline = applyBaseline(worker, baselineFiles, baselineSql);
            if (baseline.failed()) {
                return new SandboxRunResult(false, false, 0, baseline.describeFailure(),
                        outcomes, locks, List.of(), false, false, baseline);
            }
            baselineApplied = true;
            widenSearchPath(worker);

            // 2. Seed — rows and/or planner-stat stubs (UPDATE pg_class ... ; ANALYZE).
            if (seedSql != null && !seedSql.isBlank()) {
                execAll(worker, SqlScript.split(seedSql));
            }

            // Row estimates are a PRE-migration fact — capture them now, while any pg_class
            // stub in the seed is still fresh. A candidate that rewrites a table (ADD COLUMN
            // with a volatile default, ALTER COLUMN TYPE) resets reltuples, so reading sizes
            // after the migration would silently lose the simulated production scale.
            Map<String, TableStat> preSnapshot = snapshotMap(worker);

            // 3. Candidate — one statement at a time, timed, with a statement_timeout guard.
            try (Statement st = worker.createStatement()) {
                st.execute("SET statement_timeout = " + properties.getStatementTimeout().toMillis());
            }
            int backendPid = backendPid(worker);
            List<ParsedStatement> parsed = ddlParser.parse(candidateSql);
            long candidateStart = System.nanoTime();
            boolean timedOut = false;
            String failure = null;

            for (ParsedStatement ps : parsed) {
                String strongestLock = lockAnalyzer.inferStrongestLock(ps);
                long start = System.nanoTime();
                AtomicBoolean done = new AtomicBoolean(false);
                Thread poller = startLockPoller(session, backendPid, ps.normalized(), locks, done);
                try (Statement st = worker.createStatement()) {
                    st.execute(ps.raw());
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    outcomes.add(new StatementOutcome(ps.index(), ps.normalized(), true, ms, null, strongestLock));
                } catch (SQLException ex) {
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    boolean isTimeout = "57014".equals(ex.getSQLState());
                    timedOut = timedOut || isTimeout;
                    failure = ex.getMessage();
                    outcomes.add(new StatementOutcome(ps.index(), ps.normalized(), false, ms,
                            ex.getMessage(), strongestLock));
                    done.set(true);
                    joinQuietly(poller);
                    break;
                } finally {
                    done.set(true);
                    joinQuietly(poller);
                }
            }

            long candidateMs = (System.nanoTime() - candidateStart) / 1_000_000;
            boolean candidateApplied = failure == null;
            List<TableStat> after = candidateApplied
                    ? mergeSnapshots(preSnapshot, snapshotQuietly(session))
                    : new ArrayList<>(preSnapshot.values());
            return new SandboxRunResult(baselineApplied, candidateApplied, candidateMs, failure,
                    outcomes, locks, after, timedOut, true, baseline);

        } catch (SQLException ex) {
            log.warn("Sandbox replay failed before the candidate ran: {}", ex.getMessage());
            return new SandboxRunResult(baselineApplied, false, 0,
                    "sandbox setup failed: " + ex.getMessage(), outcomes, locks, List.of(), false,
                    false, baseline);
        }
    }

    /**
     * Replay the prior migrations one file at a time so a failure can be attributed to the
     * file it came from. Falls back to the flattened script when no file list was supplied
     * (evaluation cases and the legacy {@code baseline_sql} field).
     */
    private BaselineReplay applyBaseline(Connection worker, List<MigrationFile> baselineFiles, String baselineSql) {
        List<MigrationFile> files = baselineFiles == null || baselineFiles.isEmpty()
                ? syntheticHistory(baselineSql)
                : baselineFiles;
        if (files.isEmpty()) {
            return BaselineReplay.none();
        }

        int filesApplied = 0;
        int statementsApplied = 0;
        for (MigrationFile file : files) {
            List<String> statements = SqlScript.split(file.sql());
            for (String statement : statements) {
                try (Statement st = worker.createStatement()) {
                    st.execute(statement);
                    statementsApplied++;
                } catch (SQLException ex) {
                    log.warn("baseline replay stopped at {}: {}", file.filename(), ex.getMessage());
                    return new BaselineReplay(files.size(), filesApplied, statementsApplied,
                            file.filename(), abbreviate(statement), ex.getMessage());
                }
            }
            filesApplied++;
        }
        return new BaselineReplay(files.size(), filesApplied, statementsApplied, null, null, null);
    }

    /**
     * Point the sandbox at the schema the migrations expect, the way Flyway's
     * {@code schemas}/{@code create-schemas} settings do at boot. Without this, a service
     * whose migrations write into a schema of their own (identity, program, reports …) fails
     * at the first file that qualifies a name.
     *
     * <p>ALTER DATABASE makes the search path stick for every later connection: the lock
     * poller and the introspection snapshots each open their own.
     */
    private void prepareSchema(Connection worker, String targetSchema) throws SQLException {
        String schema = normalizeSchema(targetSchema);
        if (schema == null) {
            return;
        }
        String quoted = quoteIdent(schema);
        try (Statement st = worker.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + quoted);
            st.execute("ALTER DATABASE " + quoteIdent(currentDatabase(worker))
                    + " SET search_path TO " + quoted + ", public");
            st.execute("SET search_path TO " + quoted + ", public");
        }
        log.debug("sandbox search_path set to {}", schema);
    }

    /**
     * Once the history has replayed, put every schema it created on the search path.
     * Migrations routinely build across several ({@code auth}, {@code identity}, …) and the
     * row-count and size lookups resolve a bare table name through the search path — without
     * this they report "unknown" for most of the schema the review is supposed to measure.
     */
    private void widenSearchPath(Connection worker) {
        try (Statement st = worker.createStatement()) {
            List<String> schemas = new ArrayList<>();
            try (var rs = st.executeQuery(
                    "SELECT nspname FROM pg_namespace WHERE nspname NOT LIKE 'pg\\_%' "
                            + "AND nspname <> 'information_schema' ORDER BY nspname")) {
                while (rs.next()) {
                    schemas.add(quoteIdent(rs.getString(1)));
                }
            }
            if (schemas.isEmpty()) {
                return;
            }
            String path = String.join(", ", schemas);
            st.execute("ALTER DATABASE " + quoteIdent(currentDatabase(worker)) + " SET search_path TO " + path);
            st.execute("SET search_path TO " + path);
        } catch (SQLException ex) {
            log.debug("could not widen the sandbox search path: {}", ex.getMessage());
        }
    }

    /**
     * Schema names reach SQL by interpolation (identifiers cannot be bound), so anything that
     * is not a plain identifier is refused rather than escaped-and-hoped.
     */
    private String normalizeSchema(String targetSchema) {
        if (targetSchema == null || targetSchema.isBlank() || "public".equalsIgnoreCase(targetSchema.trim())) {
            return null;
        }
        String schema = targetSchema.trim();
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_$]{0,62}")) {
            throw new IllegalArgumentException("Not a valid schema name: " + targetSchema);
        }
        return schema;
    }

    private String currentDatabase(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT current_database()")) {
            return rs.next() ? rs.getString(1) : "postgres";
        }
    }

    private String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private List<MigrationFile> syntheticHistory(String baselineSql) {
        if (baselineSql == null || baselineSql.isBlank()) {
            return List.of();
        }
        return List.of(new MigrationFile("baseline.sql", baselineSql));
    }

    private String abbreviate(String statement) {
        String flat = statement.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + " …";
    }

    private void execAll(Connection c, List<String> statements) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String s : statements) {
                st.execute(s);
            }
        }
    }

    private int backendPid(Connection c) {
        try (Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT pg_backend_pid()")) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException e) {
            return -1;
        }
    }

    private Thread startLockPoller(SandboxSession session, int backendPid, String statement,
                                   List<LockObservation> sink, AtomicBoolean done) {
        if (backendPid < 0) {
            return null;
        }
        Thread t = new Thread(() -> {
            try (Connection probe = session.open()) {
                while (!done.get()) {
                    List<LockObservation> found = lockAnalyzer.pollLocks(probe, backendPid, statement);
                    synchronized (sink) {
                        for (LockObservation lo : found) {
                            boolean dup = sink.stream().anyMatch(x -> x.relation().equals(lo.relation())
                                    && x.lockMode().equals(lo.lockMode()) && x.statement().equals(lo.statement()));
                            if (!dup) {
                                sink.add(lo);
                            }
                        }
                    }
                    Thread.sleep(5);
                }
            } catch (Exception ignored) {
                // best-effort
            }
        }, "sandbox-lock-poller");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void joinQuietly(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<TableStat> snapshotQuietly(SandboxSession session) {
        // Deliberately no global ANALYZE here: evaluation cases stub pg_class.reltuples to
        // simulate a production-scale table without inserting millions of rows, and an
        // ANALYZE would wipe that.
        try (Connection c = session.open()) {
            return new ArrayList<>(introspector.snapshot(c).values());
        } catch (SQLException e) {
            log.debug("post-migration snapshot failed: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, TableStat> snapshotMap(Connection c) {
        try {
            return introspector.snapshot(c);
        } catch (SQLException e) {
            log.debug("pre-migration snapshot failed: {}", e.getMessage());
            return new java.util.LinkedHashMap<>();
        }
    }

    /** Columns / indexes / FKs come from the post state; row estimates from the pre state. */
    private List<TableStat> mergeSnapshots(Map<String, TableStat> pre, List<TableStat> post) {
        List<TableStat> merged = new ArrayList<>();
        for (TableStat p : post) {
            TableStat before = pre.get(p.table());
            if (before == null) {
                merged.add(p);
            } else {
                merged.add(new TableStat(p.table(), before.estimatedRows(), before.exactRows(),
                        before.sizeBytes(), p.columns(), p.indexes(), p.foreignKeys()));
            }
        }
        return merged;
    }
}
