package com.migrationsentinel.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "evaluation_case_result", indexes = {
        @Index(name = "ix_eval_case_result_run_id", columnList = "evaluation_run_id")
})
public class EvaluationCaseResultEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evaluation_run_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_eval_case_result_run"))
    private EvaluationRunEntity evaluationRun;

    @Column(name = "case_id", nullable = false, length = 64)
    private String caseId;

    @Column(name = "review_job_id")
    private UUID reviewJobId;

    @Column(name = "expected_count", nullable = false)
    private int expectedCount;

    @Column(name = "reported_count", nullable = false)
    private int reportedCount;

    @Column(name = "true_positives", nullable = false)
    private int truePositives;

    @Column(name = "false_positives", nullable = false)
    private int falsePositives;

    @Column(name = "false_negatives", nullable = false)
    private int falseNegatives;

    @Column(nullable = false)
    private boolean passed;

    @Column(columnDefinition = "text")
    private String notes;
}
