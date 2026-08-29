package com.migrationsentinel.model.entity;

import com.migrationsentinel.model.enums.FindingVerdict;
import com.migrationsentinel.model.enums.RuleCode;
import com.migrationsentinel.model.enums.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "finding", indexes = {
        @Index(name = "ix_finding_review_job_id", columnList = "review_job_id"),
        @Index(name = "ix_finding_rule_code", columnList = "rule_code")
})
public class FindingEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_job_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_finding_review_job"))
    private ReviewJobEntity reviewJob;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_code", nullable = false, length = 48)
    private RuleCode ruleCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "target_object", length = 255)
    private String targetObject;

    @Column(columnDefinition = "text", nullable = false)
    private String summary;

    /** The concrete tool output that grounds this finding (row counts, EXPLAIN, lock observations). */
    @Column(columnDefinition = "text")
    private String evidence;

    /** Suggested corrected SQL. Text only — never written to disk without an explicit human action. */
    @Column(name = "suggested_rewrite", columnDefinition = "text")
    private String suggestedRewrite;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FindingVerdict verdict = FindingVerdict.UNVERIFIED;

    @Column(name = "analyzer_confidence")
    private Double analyzerConfidence;
}
