package com.migrationsentinel.model.entity;

import com.migrationsentinel.model.enums.OutboxStatus;
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
@Table(name = "outbox_event", indexes = {
        @Index(name = "ix_outbox_event_due", columnList = "status, next_attempt_at")
})
public class OutboxEventEntity extends BaseEntity {

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", length = 64)
    private String aggregateId;

    @Column(nullable = false, length = 128)
    private String topic;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;
}
