package com.migrationsentinel.service.sandbox;

import com.migrationsentinel.config.properties.SandboxProperties;
import com.migrationsentinel.payload.dto.LockObservation;
import com.migrationsentinel.payload.dto.SandboxRunResult;
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
        try (Connection worker = session.open()) {
            worker.setAutoCommit(true);
            if (baselineSql != null && !baselineSql.isBlank()) {
                execAll(worker, SqlScript.split(baselineSql));
            }
            if (seedSql != null && !seedSql.isBlank()) {
                execAll(worker, SqlScript.split(seedSql));
            }
            List<TableStat> stats = new ArrayList<>(snapshotMap(worker).values());
            return new SandboxRunResult(true, false, 0, null, List.of(), List.of(), stats, false);
        } catch (SQLException ex) {
            return SandboxRunResult.unavailable("sandbox setup failed: " + ex.getMessage());
        }
    }

    public SandboxRunResult replay(SandboxSession session, String baselineSql, String seedSql, String candidateSql) {
        List<StatementOutcome> outcomes = new ArrayList<>();
        List<LockObservation> locks = new ArrayList<>();
        boolean baselineApplied = false;

        try (Connection worker = session.open()) {
            worker.setAutoCommit(true);

            // 1. Baseline — prior migration history. A failure here is a setup problem, not a finding.
            if (baselineSql != null && !baselineSql.isBlank()) {
                execAll(worker, SqlScript.split(baselineSql));
            }
            baselineApplied = true;

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
                    outcomes, locks, after, timedOut);

        } catch (SQLException ex) {
            log.warn("Sandbox replay failed before the candidate ran: {}", ex.getMessage());
            return new SandboxRunResult(baselineApplied, false, 0,
                    "sandbox setup failed: " + ex.getMessage(), outcomes, locks, List.of(), false);
        }
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
