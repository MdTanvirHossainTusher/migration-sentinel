package com.migrationsentinel.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sentinel.sandbox")
public class SandboxProperties {

    /** Docker image for the disposable sandbox database. Pinned for reproducibility. */
    private String postgresImage = "postgres:16-alpine";

    /** Hard ceiling on how long a single candidate migration may run in the sandbox. */
    private Duration migrationTimeout = Duration.ofSeconds(60);

    /** Statement timeout applied inside the sandbox session while the candidate migration runs. */
    private Duration statementTimeout = Duration.ofSeconds(45);

    /** Reuse one container across a whole evaluation run instead of one per case (faster, still disposable). */
    private boolean reuseWithinEvaluation = true;
}
