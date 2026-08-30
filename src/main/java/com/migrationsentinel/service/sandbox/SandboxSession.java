package com.migrationsentinel.service.sandbox;

import com.migrationsentinel.exception.SandboxSafetyException;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * A live handle on one disposable sandbox database. The JDBC URL is fixed at creation
 * and every connection is checked against it — a tool cannot point DDL anywhere else.
 * Closing the session destroys the container.
 */
public final class SandboxSession implements AutoCloseable {

    private final PostgreSQLContainer<?> container;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    /**
     * Whether closing this handle destroys the container. False for a handle borrowed from a
     * longer-lived lease (an evaluation run reusing one container across its cases): the
     * borrower must not tear down a container it did not start.
     */
    private final boolean owned;

    SandboxSession(PostgreSQLContainer<?> container) {
        this(container, true);
    }

    private SandboxSession(PostgreSQLContainer<?> container, boolean owned) {
        this.container = container;
        this.jdbcUrl = container.getJdbcUrl();
        this.username = container.getUsername();
        this.password = container.getPassword();
        this.owned = owned;
    }

    /**
     * A handle on the same container whose {@link #close()} is a no-op. The safety guard is
     * unchanged — it still refuses any URL that is not this sandbox's own.
     */
    SandboxSession borrow() {
        return new SandboxSession(container, false);
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    /**
     * The single guard every write path funnels through. If the URL handed in is not this
     * sandbox's own URL, refuse — see docs/SAFETY_MODEL.md.
     */
    public void assertIsSandbox(String candidateUrl) {
        if (!jdbcUrl.equals(candidateUrl)) {
            throw new SandboxSafetyException(
                    "Refusing DDL against a datasource that is not the disposable sandbox: " + candidateUrl);
        }
    }

    public Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(jdbcUrl, username, password);
        assertIsSandbox(c.getMetaData().getURL());
        return c;
    }

    public boolean isRunning() {
        return container.isRunning();
    }

    @Override
    public void close() {
        if (owned) {
            container.stop();
        }
    }
}
