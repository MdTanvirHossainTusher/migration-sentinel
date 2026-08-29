package com.migrationsentinel.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Audit trail for the one consequential action the system can take: writing a suggested
 * rewrite to disk. Every row here corresponds to an explicit human click. See docs/SAFETY_MODEL.md.
 */
@Getter
@Setter
@Entity
@Table(name = "approval_record", indexes = {
        @Index(name = "ix_approval_record_review_job_id", columnList = "review_job_id")
})
public class ApprovalRecordEntity extends BaseEntity {

    @Column(name = "review_job_id", nullable = false)
    private UUID reviewJobId;

    @Column(name = "finding_id")
    private UUID findingId;

    @Column(nullable = false, length = 32)
    private String action = "APPLY_REWRITE";

    @Column(name = "approved_by", nullable = false, length = 255)
    private String approvedBy;

    @Column(name = "target_path", nullable = false, length = 1024)
    private String targetPath;

    @Column(nullable = false)
    private boolean applied;

    @Column(columnDefinition = "text")
    private String note;
}
