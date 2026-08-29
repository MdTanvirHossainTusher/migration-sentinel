package com.migrationsentinel.model.entity;

import com.migrationsentinel.model.enums.EvaluationStatus;
import com.migrationsentinel.model.enums.ReviewMode;
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
@Table(name = "evaluation_run", indexes = {
        @Index(name = "ix_evaluation_run_created_at", columnList = "created_at")
})
public class EvaluationRunEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EvaluationStatus status = EvaluationStatus.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReviewMode mode;

    @Column(name = "llm_provider", length = 32)
    private String llmProvider;

    @Column(name = "corpus_label", length = 64)
    private String corpusLabel;

    @Column(name = "total_cases")
    private int totalCases;

    @Column(name = "completed_cases")
    private int completedCases;

    @Column(name = "true_positives")
    private int truePositives;

    @Column(name = "false_positives")
    private int falsePositives;

    @Column(name = "false_negatives")
    private int falseNegatives;

    private Double precision;
    private Double recall;
    private Double f1;

    @Column(name = "false_positive_rate")
    private Double falsePositiveRate;

    @Column(name = "mean_duration_ms")
    private Long meanDurationMs;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
