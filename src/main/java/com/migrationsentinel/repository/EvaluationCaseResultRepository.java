package com.migrationsentinel.repository;

import com.migrationsentinel.model.entity.EvaluationCaseResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EvaluationCaseResultRepository extends JpaRepository<EvaluationCaseResultEntity, UUID> {

    List<EvaluationCaseResultEntity> findByEvaluationRunIdOrderByCaseIdAsc(UUID evaluationRunId);
}
