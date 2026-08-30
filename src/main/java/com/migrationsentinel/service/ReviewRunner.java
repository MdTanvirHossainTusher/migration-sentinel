package com.migrationsentinel.service;

import com.migrationsentinel.model.entity.FindingEntity;
import com.migrationsentinel.model.entity.ReviewJobEntity;
import com.migrationsentinel.model.entity.ToolCallEntity;
import com.migrationsentinel.model.enums.ReviewStatus;
import com.migrationsentinel.payload.dto.MigrationInput;
import com.migrationsentinel.payload.dto.SandboxRunResult;
import com.migrationsentinel.payload.dto.VerifiedFinding;
import com.migrationsentinel.repository.FindingRepository;
import com.migrationsentinel.repository.ReviewJobRepository;
import com.migrationsentinel.repository.ToolCallRepository;
import com.migrationsentinel.service.agent.MigrationReviewOrchestrator;
import com.migrationsentinel.service.agent.RecordedToolCall;
import com.migrationsentinel.service.agent.TrajectoryRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Executes a persisted {@link ReviewJobEntity}: runs the agent pipeline, then writes the
 * findings, the full tool-call trajectory and the Markdown report back to the row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewRunner {

    private final ReviewJobRepository reviewJobRepository;
    private final FindingRepository findingRepository;
    private final ToolCallRepository toolCallRepository;
    private final MigrationReviewOrchestrator orchestrator;

    @Async("reviewExecutor")
    public void runAsync(UUID jobId) {
        try {
            execute(jobId);
        } catch (Exception ex) {
            log.error("review {} failed", jobId, ex);
            markFailed(jobId, ex.getMessage());
        }
    }

    /** Synchronous path used by the evaluation harness. */
    public ReviewJobEntity runSync(UUID jobId) {
        try {
            return execute(jobId);
        } catch (Exception ex) {
            log.error("review {} failed", jobId, ex);
            return markFailed(jobId, ex.getMessage());
        }
    }

    private ReviewJobEntity execute(UUID jobId) {
        ReviewJobEntity job = reviewJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("review job vanished: " + jobId));
        job.setStatus(ReviewStatus.RUNNING);
        job.setStartedAt(Instant.now());
        reviewJobRepository.saveAndFlush(job);

        MigrationInput input = new MigrationInput(
                job.getMigrationFilename(), job.getMigrationSql(), job.getBaselineSql(),
                MigrationHistory.split(job.getBaselineSql()), job.getTargetSchema(),
                job.getSeedSql(), job.getEntitySource(), job.getMode(), job.getLlmProvider(), job.getCaseId());

        TrajectoryRecorder recorder = new TrajectoryRecorder();
        long start = System.currentTimeMillis();
        MigrationReviewOrchestrator.Result result = orchestrator.review(input, recorder);
        long durationMs = System.currentTimeMillis() - start;

        persistFindings(job, result.findings());
        persistTrajectory(job, recorder.calls());

        job.setStatus(ReviewStatus.COMPLETED);
        job.setFinishedAt(Instant.now());
        job.setDurationMs(durationMs);
        job.setFindingsCount(result.findings().size());
        job.setToolCallCount(recorder.count());
        job.setSandboxUsed(result.sandboxUsed());
        job.setSandboxNote(sandboxNote(result));
        job.setReportMarkdown(result.reportMarkdown());
        return reviewJobRepository.saveAndFlush(job);
    }

    /**
     * Why the sandbox produced nothing, when it produced nothing. A review that silently
     * degrades to structure-only reads as "clean" — the reason has to travel with it.
     */
    private String sandboxNote(MigrationReviewOrchestrator.Result result) {
        SandboxRunResult run = result.sandboxRun();
        if (run == null) {
            return null;
        }
        SandboxRunResult.BaselineReplay baseline = run.baselineOrNone();
        if (baseline.failed()) {
            return baseline.describeFailure();
        }
        if (!result.sandboxUsed() && run.failureMessage() != null) {
            return run.failureMessage();
        }
        return null;
    }

    private void persistFindings(ReviewJobEntity job, List<VerifiedFinding> findings) {
        int ordinal = 1;
        for (VerifiedFinding vf : findings) {
            FindingEntity e = new FindingEntity();
            e.setReviewJob(job);
            e.setOrdinal(ordinal++);
            e.setRuleCode(vf.finding().ruleCode());
            e.setSeverity(vf.finding().severity());
            e.setTitle(trim(vf.finding().title(), 255));
            e.setTargetObject(trim(vf.finding().targetObject(), 255));
            e.setSummary(nullSafe(vf.finding().summary()));
            e.setEvidence(vf.finding().evidence());
            e.setSuggestedRewrite(vf.finding().suggestedRewrite());
            e.setVerdict(vf.verdict());
            e.setAnalyzerConfidence(vf.finding().confidence());
            findingRepository.save(e);
        }
    }

    private void persistTrajectory(ReviewJobEntity job, List<RecordedToolCall> calls) {
        for (RecordedToolCall c : calls) {
            ToolCallEntity e = new ToolCallEntity();
            e.setReviewJob(job);
            e.setAgentRole(c.agentRole());
            e.setStepNo(c.stepNo());
            e.setToolName(trim(c.toolName(), 64));
            e.setArgumentsJson(c.argumentsJson());
            e.setResultJson(c.resultJson());
            e.setDurationMs(c.durationMs());
            e.setOk(c.ok());
            toolCallRepository.save(e);
        }
    }

    private ReviewJobEntity markFailed(UUID jobId, String message) {
        ReviewJobEntity job = reviewJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return null;
        }
        job.setStatus(ReviewStatus.FAILED);
        job.setFinishedAt(Instant.now());
        job.setErrorMessage(trim(message, 4000));
        return reviewJobRepository.saveAndFlush(job);
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
