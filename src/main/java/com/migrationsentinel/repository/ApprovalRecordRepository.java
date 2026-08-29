package com.migrationsentinel.repository;

import com.migrationsentinel.model.entity.ApprovalRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecordEntity, UUID> {

    List<ApprovalRecordEntity> findByReviewJobIdOrderByCreatedAtDesc(UUID reviewJobId);
}
