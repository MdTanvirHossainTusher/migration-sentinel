package com.migrationsentinel.repository;

import com.migrationsentinel.model.entity.AuditEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {

    Page<AuditEventEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<AuditEventEntity> findByAggregateTypeAndAggregateIdOrderByCreatedAtDesc(
            String aggregateType, String aggregateId, Pageable pageable);
}
