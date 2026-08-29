package com.migrationsentinel.repository;

import com.migrationsentinel.model.entity.EvaluationRunEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EvaluationRunRepository extends JpaRepository<EvaluationRunEntity, UUID> {

    Page<EvaluationRunEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
