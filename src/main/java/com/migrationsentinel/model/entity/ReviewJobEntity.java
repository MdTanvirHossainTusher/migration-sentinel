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

    /** Prior migrations replayed into the sandbox before the candidate, flattened in Flyway version order. */
    @Column(name = "baseline_sql", columnDefinition = "text")
    private String baselineSql;

    /** How many prior migration files the baseline was built from. */
    @Column(name = "baseline_file_count")
    private int baselineFileCount;

    /** The ordered filenames behind {@link #baselineSql}, as a JSON array, for display. */
    @Column(name = "baseline_files_json", columnDefinition = "text")
    private String baselineFilesJson;

    /**
     * Why the sandbox produced no measurements, when it produced none — most often a prior
     * migration that failed to replay. Distinct from {@link #errorMessage}, which means the
     * review itself crashed.
     */
    @Column(name = "sandbox_note", columnDefinition = "text")
    private String sandboxNote;

    /** Schema the migrations build into (the project's Flyway {@code schemas}); null means public. */
    @Column(name = "target_schema", length = 63)
    private String targetSchema;

    /** Optional seed SQL (or {@code UPDATE pg_class ...} row-estimate stubs) applied after the baseline. */
    @Column(name = "seed_sql", columnDefinition = "text")
    private String seedSql;

    /** Optional JPA entity source used for the entity/schema drift check. */
    @Column(name = "entity_source", columnDefinition = "text")
    private String entitySource;

    @Column(name = "llm_provider", length = 32)
    private String llmProvider;

    /** Per-request LLM API key, AES-GCM encrypted. Never serialized to any response. */
    @Column(name = "llm_api_key_encrypted", columnDefinition = "text")
    private String llmApiKeyEncrypted;

    @Column(name = "case_id", length = 64)
    private String caseId;

    @Column(name = "report_markdown", columnDefinition = "text")
    private String reportMarkdown;

    /** Object-storage artifact holding the rendered report.md, when S3 storage is enabled. */
    @Column(name = "report_artifact_id")
    private java.util.UUID reportArtifactId;

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
