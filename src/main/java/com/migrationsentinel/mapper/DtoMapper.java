package com.migrationsentinel.mapper;

import com.migrationsentinel.model.entity.ApprovalRecordEntity;
import com.migrationsentinel.model.entity.EvaluationCaseResultEntity;
import com.migrationsentinel.model.entity.EvaluationRunEntity;
import com.migrationsentinel.model.entity.FindingEntity;
import com.migrationsentinel.model.entity.ReviewJobEntity;
import com.migrationsentinel.model.entity.ToolCallEntity;
import com.migrationsentinel.model.enums.Severity;
import com.migrationsentinel.payload.response.ApprovalRecordResponse;
import com.migrationsentinel.payload.response.EvaluationCaseResultResponse;
import com.migrationsentinel.payload.response.EvaluationRunResponse;
import com.migrationsentinel.payload.response.FindingResponse;
import com.migrationsentinel.payload.response.ReviewResponse;
import com.migrationsentinel.payload.response.ToolCallResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DtoMapper {

    public ReviewResponse toReviewResponse(ReviewJobEntity job, List<FindingEntity> findings) {
        return new ReviewResponse(
                job.getId(),
                job.getStatus(),
                job.getMode(),
                job.getLlmProvider(),
                job.getMigrationFilename(),
                job.getCaseId(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getDurationMs(),
                job.getFindingsCount(),
                job.getToolCallCount(),
                job.isSandboxUsed(),
                job.getSandboxNote(),
                job.getBaselineFileCount(),
                (int) countSeverity(findings, Severity.HIGH),
                (int) countSeverity(findings, Severity.MEDIUM),
                (int) countSeverity(findings, Severity.LOW),
                job.getErrorMessage());
    }

    public FindingResponse toFindingResponse(FindingEntity f) {
        return new FindingResponse(
                f.getId(), f.getOrdinal(), f.getRuleCode(), f.getSeverity(), f.getTitle(),
                f.getTargetObject(), f.getSummary(), f.getEvidence(), f.getSuggestedRewrite(),
                f.getVerdict(), f.getAnalyzerConfidence());
    }

    public ToolCallResponse toToolCallResponse(ToolCallEntity t) {
        return new ToolCallResponse(
                t.getId(), t.getAgentRole(), t.getStepNo(), t.getToolName(),
                t.getArgumentsJson(), t.getResultJson(), t.getDurationMs(), t.isOk());
    }

    public EvaluationRunResponse toEvaluationRunResponse(EvaluationRunEntity r) {
        return new EvaluationRunResponse(
                r.getId(), r.getStatus(), r.getMode(), r.getLlmProvider(), r.getCorpusLabel(),
                r.getTotalCases(), r.getCompletedCases(), r.getTruePositives(), r.getFalsePositives(),
                r.getFalseNegatives(), r.getPrecision(), r.getRecall(), r.getF1(), r.getFalsePositiveRate(),
                r.getMeanDurationMs(), r.getCreatedAt(), r.getFinishedAt(), r.getErrorMessage());
    }

    public EvaluationCaseResultResponse toCaseResultResponse(EvaluationCaseResultEntity c) {
        return new EvaluationCaseResultResponse(
                c.getCaseId(), c.getExpectedCount(), c.getReportedCount(), c.getTruePositives(),
                c.getFalsePositives(), c.getFalseNegatives(), c.isPassed(), c.getNotes(), c.getReviewJobId());
    }

    public ApprovalRecordResponse toApprovalResponse(ApprovalRecordEntity a) {
        return new ApprovalRecordResponse(
                a.getId(), a.getReviewJobId(), a.getFindingId(), a.getAction(), a.getApprovedBy(),
                a.getTargetPath(), a.isApplied(), a.getNote(), a.getCreatedAt());
    }

    private long countSeverity(List<FindingEntity> findings, Severity severity) {
        return findings.stream().filter(f -> f.getSeverity() == severity).count();
    }
}
