package com.migrationsentinel.repository;

import com.migrationsentinel.model.entity.FindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FindingRepository extends JpaRepository<FindingEntity, UUID> {

    List<FindingEntity> findByReviewJobIdOrderByOrdinalAsc(UUID reviewJobId);
}
