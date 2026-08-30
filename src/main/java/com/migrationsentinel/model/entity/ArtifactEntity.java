package com.migrationsentinel.model.entity;

import com.migrationsentinel.model.enums.ArtifactKind;
import com.migrationsentinel.model.enums.ArtifactStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "artifact", indexes = {
        @Index(name = "ix_artifact_review_job_id", columnList = "review_job_id"),
        @Index(name = "ix_artifact_status", columnList = "status")
})
public class ArtifactEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ArtifactKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ArtifactStatus status = ArtifactStatus.PENDING;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "review_job_id")
    private UUID reviewJobId;

    @Column(name = "created_by", length = 128)
    private String createdBy;
}
