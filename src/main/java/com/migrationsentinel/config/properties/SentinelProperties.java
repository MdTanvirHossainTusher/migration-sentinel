package com.migrationsentinel.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sentinel")
public class SentinelProperties {

    /** Origins allowed to call the API (the Next.js frontend). */
    private List<String> corsAllowedOrigins = List.of("http://localhost:3000");

    /** Classpath or filesystem root that holds the evaluation case corpus. */
    private String evalCorpusPath = "classpath:eval/cases";

    /** Absolute row estimate above which an ACCESS EXCLUSIVE rewrite/lock is treated as HIGH severity. */
    private long largeTableRowThreshold = 1_000_000L;

    /** Directory the "apply rewrite" endpoint is allowed to write into (must be an explicit human action). */
    private String rewriteOutputDir = "./rewrites";

    /** Master switch for the apply-rewrite endpoint. Off by default. */
    private boolean rewriteApplyEnabled = false;
}
