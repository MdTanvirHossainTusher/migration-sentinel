package com.migrationsentinel.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "audit_event", indexes = {
        @Index(name = "ix_audit_event_created_at", columnList = "created_at"),
        @Index(name = "ix_audit_event_aggregate", columnList = "aggregate_type, aggregate_id"),
        @Index(name = "ix_audit_event_type", columnList = "event_type")
})
public class AuditEventEntity extends BaseEntity {

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", length = 64)
    private String aggregateId;

    @Column(length = 128)
    private String actor;

    @Column(length = 500)
    private String summary;

    @Column(columnDefinition = "text")
    private String payload;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;
}
