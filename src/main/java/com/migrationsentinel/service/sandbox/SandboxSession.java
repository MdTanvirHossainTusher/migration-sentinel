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

    SandboxSession(PostgreSQLContainer<?> container) {
        this.container = container;
        this.jdbcUrl = container.getJdbcUrl();
        this.username = container.getUsername();
        this.password = container.getPassword();
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
        container.stop();
    }
}
