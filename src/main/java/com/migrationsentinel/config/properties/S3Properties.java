package com.migrationsentinel.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Object storage for downloadable artifacts — the rendered {@code report.md} and any file a
 * user uploads for review. Works against any S3 API; the compose stack points it at RustFS.
 * Off by default: with {@code enabled=false} the report stays inline in the JSON response,
 * exactly as before.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sentinel.s3")
public class S3Properties {

    /**
     * Every value here is supplied by {@code application.yaml}, which reads it from the
     * environment (see {@code .env} / {@code .env.example} for the compose stack). Nothing
     * sensitive is hard-coded — an unset credential just means "S3 not usable".
     */
    private boolean enabled = false;

    /** Endpoint the service itself calls (server-to-server). */
    private String endpointInternal;

    /** Endpoint baked into presigned URLs handed to the browser. */
    private String endpointPublic;

    private String bucket;
    private String accessKey;
    private String secretKey;
    private String region;

    /** RustFS / MinIO need path-style addressing (bucket in the path, not the host). */
    private boolean pathStyleAccess = true;

    /** How long a presigned upload or download URL stays valid. */
    private Duration presignExpiry = Duration.ofMinutes(15);

    /** Largest artifact accepted, enforced on the upload request and re-checked on confirm. */
    private DataSize maxFileSize = DataSize.ofMegabytes(10);
}
