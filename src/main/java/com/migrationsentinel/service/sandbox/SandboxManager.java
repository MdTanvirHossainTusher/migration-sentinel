package com.migrationsentinel.service.sandbox;

import com.migrationsentinel.config.properties.SandboxProperties;
import com.migrationsentinel.exception.SandboxUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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

    public boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            log.debug("Docker availability probe failed: {}", ex.getMessage());
            return false;
        }
    }

    public SandboxSession start() {
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
}
