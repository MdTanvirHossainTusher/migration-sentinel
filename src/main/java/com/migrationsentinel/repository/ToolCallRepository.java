package com.migrationsentinel.repository;

import com.migrationsentinel.model.entity.ToolCallEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ToolCallRepository extends JpaRepository<ToolCallEntity, UUID> {

    List<ToolCallEntity> findByReviewJobIdOrderByStepNoAsc(UUID reviewJobId);
}
