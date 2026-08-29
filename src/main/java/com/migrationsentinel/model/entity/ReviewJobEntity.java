package com.migrationsentinel.model.entity;

import com.migrationsentinel.model.enums.ReviewMode;
import com.migrationsentinel.model.enums.ReviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "review_job", indexes = {
        @Index(name = "ix_review_job_status", columnList = "status"),
        @Index(name = "ix_review_job_created_at", columnList = "created_at")
})
public class ReviewJobEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReviewStatus status = ReviewStatus.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReviewMode mode = ReviewMode.ANALYZER_VERIFIER_SPLIT;

    @Column(name = "migration_filename", length = 255)
    private String migrationFilename;

    @Column(name = "migration_sql", columnDefinition = "text", nullable = false)
    private String migrationSql;

    /** Prior migrations replayed into the sandbox before the candidate. Newline-separated file bodies. */
    @Column(name = "baseline_sql", columnDefinition = "text")
    private String baselineSql;

    /** Optional seed SQL (or {@code UPDATE pg_class ...} row-estimate stubs) applied after the baseline. */
    @Column(name = "seed_sql", columnDefinition = "text")
    private String seedSql;

    /** Optional JPA entity source used for the entity/schema drift check. */
    @Column(name = "entity_source", columnDefinition = "text")
    private String entitySource;

    @Column(name = "llm_provider", length = 32)
    private String llmProvider;

    @Column(name = "case_id", length = 64)
    private String caseId;

    @Column(name = "report_markdown", columnDefinition = "text")
    private String reportMarkdown;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "tool_call_count")
    private int toolCallCount;

    @Column(name = "findings_count")
    private int findingsCount;

    @Column(name = "sandbox_used")
    private boolean sandboxUsed;
}
