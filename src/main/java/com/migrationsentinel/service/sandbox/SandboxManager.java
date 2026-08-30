package com.migrationsentinel.service.sandbox;

import com.migrationsentinel.config.properties.SandboxProperties;
import com.migrationsentinel.exception.SandboxUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the lifecycle of the disposable sandbox database. Nothing else in the codebase
 * constructs a container or a datasource for the reviewed migration — this is the only
 * door, and it never takes a connection string from the caller.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxManager {

    private final SandboxProperties properties;

    /**
     * A container leased for a whole evaluation run. Bound to the thread that opened it —
     * {@code EvaluationRunner} drives its cases synchronously on one thread — so the reviews
     * it runs pick the lease up without any tool or agent signature learning about it.
     */
    private final ThreadLocal<SandboxSession> leased = new ThreadLocal<>();

    /**
     * Lease one container for the caller's whole evaluation run instead of starting a fresh
     * one per case. 15 cases x 4 sandbox-using modes is 60 container starts, which is minutes
     * of pure churn and what made the evaluation harness time out in CI.
     *
     * <p>Still disposable: the container is created here, destroyed when the lease closes, and
     * wiped back to an empty database between cases, so no case can observe another's schema.
     * Returns a no-op lease when reuse is disabled or Docker is unreachable, and the caller
     * falls back to per-case containers.
     */
    public Lease leaseForEvaluation() {
        if (!properties.isReuseWithinEvaluation() || !dockerAvailable() || leased.get() != null) {
            return () -> {
            };
        }
        SandboxSession session = start();
        leased.set(session);
        log.info("Evaluation lease opened on {}", session.jdbcUrl());
        return () -> {
            leased.remove();
            try {
                session.close();
            } catch (RuntimeException ex) {
                log.warn("evaluation lease teardown failed: {}", ex.getMessage());
            }
        };
    }

    /** Closing a lease releases the shared container. */
    @FunctionalInterface
    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    public boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            log.debug("Docker availability probe failed: {}", ex.getMessage());
            return false;
        }
    }

    public SandboxSession start() {
        SandboxSession lease = leased.get();
        if (lease != null && lease.isRunning()) {
            wipe(lease);
            return lease.borrow();
        }
        if (!dockerAvailable()) {
            throw new SandboxUnavailableException(
                    "No Docker daemon is reachable. The sandbox tools need one; run with sentinel.sandbox "
                            + "disabled or start Docker. Structure-only review still works.");
        }
        DockerImageName image = DockerImageName.parse(properties.getPostgresImage())
                .asCompatibleSubstituteFor("postgres");
        @SuppressWarnings("resource")
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(image)
                .withDatabaseName("sandbox")
                .withUsername("sentinel")
                .withPassword("sentinel")
                .withCommand("postgres", "-c", "fsync=off", "-c", "full_page_writes=off",
                        "-c", "synchronous_commit=off");
        container.start();
        log.info("Sandbox Postgres started: {}", container.getJdbcUrl());
        return new SandboxSession(container);
    }

    /**
     * Return a leased container to an empty state between cases. Migrations create schemas of
     * their own and the replayer pins a search path at the database level, so dropping
     * {@code public} alone would leak both into the next case and silently corrupt the
     * evaluation.
     */
    private void wipe(SandboxSession session) {
        try (Connection c = session.open(); Statement st = c.createStatement()) {
            c.setAutoCommit(true);
            List<String> schemas = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT nspname FROM pg_namespace "
                            + "WHERE nspname NOT LIKE 'pg\\_%' AND nspname <> 'information_schema'")) {
                while (rs.next()) {
                    schemas.add(rs.getString(1));
                }
            }
            for (String schema : schemas) {
                st.execute("DROP SCHEMA IF EXISTS \"" + schema.replace("\"", "\"\"") + "\" CASCADE");
            }
            st.execute("CREATE SCHEMA IF NOT EXISTS public");
            // Undo any ALTER DATABASE ... SET search_path a previous case left behind.
            st.execute("ALTER DATABASE " + quoteCurrentDatabase(c) + " RESET ALL");
            st.execute("SET search_path TO public");
        } catch (SQLException ex) {
            throw new SandboxUnavailableException(
                    "Could not reset the leased sandbox between evaluation cases: " + ex.getMessage());
        }
    }

    private String quoteCurrentDatabase(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_database()")) {
            String db = rs.next() ? rs.getString(1) : "postgres";
            return "\"" + db.replace("\"", "\"\"") + "\"";
        }
    }
}
