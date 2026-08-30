package com.migrationsentinel.service.eval;

import com.migrationsentinel.model.entity.EvaluationCaseResultEntity;
import com.migrationsentinel.model.entity.EvaluationRunEntity;
import com.migrationsentinel.model.entity.FindingEntity;
import com.migrationsentinel.model.entity.ReviewJobEntity;
import com.migrationsentinel.model.enums.EvaluationStatus;
import com.migrationsentinel.model.enums.ReviewStatus;
import com.migrationsentinel.repository.EvaluationCaseResultRepository;
import com.migrationsentinel.repository.EvaluationRunRepository;
import com.migrationsentinel.repository.FindingRepository;
import com.migrationsentinel.repository.ReviewJobRepository;
import com.migrationsentinel.model.enums.ReviewMode;
import com.migrationsentinel.service.ReviewRunner;
import com.migrationsentinel.service.sandbox.SandboxManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationRunner {

    private final EvaluationRunRepository evaluationRunRepository;
    private final EvaluationCaseResultRepository caseResultRepository;
    private final ReviewJobRepository reviewJobRepository;
    private final FindingRepository findingRepository;
    private final EvaluationCorpus corpus;
    private final EvaluationScorer scorer;
    private final ReviewRunner reviewRunner;
    private final SandboxManager sandboxManager;

    @Async("reviewExecutor")
    public void runAsync(UUID runId, List<String> caseIds) {
        EvaluationRunEntity run = evaluationRunRepository.findById(runId).orElseThrow();
        try {
            List<EvaluationCase> cases = corpus.subset(caseIds);
            run.setStatus(EvaluationStatus.RUNNING);
            run.setTotalCases(cases.size());
            evaluationRunRepository.saveAndFlush(run);

            int tp = 0;
            int fp = 0;
            int fn = 0;
            long totalDuration = 0;
            int done = 0;

            // One container for the whole run, wiped between cases, instead of one per case.
            // BASELINE_PROMPT never touches the sandbox, so it does not pay for a lease.
            try (SandboxManager.Lease lease = run.getMode() == ReviewMode.BASELINE_PROMPT
                    ? () -> { }
                    : sandboxManager.leaseForEvaluation()) {
                for (EvaluationCase testCase : cases) {
                    ReviewJobEntity job = new ReviewJobEntity();
                    job.setStatus(ReviewStatus.QUEUED);
                    job.setMode(run.getMode());
                    job.setLlmProvider(run.getLlmProvider());
                    job.setCaseId(testCase.id());
                    job.setMigrationFilename(testCase.id() + "/migration.sql");
                    job.setMigrationSql(testCase.migrationSql());
                    job.setBaselineSql(testCase.baselineSql());
                    job.setSeedSql(testCase.seedSql());
                    job.setEntitySource(testCase.entitySource());
                    job = reviewJobRepository.saveAndFlush(job);

                    ReviewJobEntity completed = reviewRunner.runSync(job.getId());
                    List<FindingEntity> findings = findingRepository.findByReviewJobIdOrderByOrdinalAsc(job.getId());
                    CaseScore score = scorer.score(testCase, findings);

                    EvaluationCaseResultEntity result = new EvaluationCaseResultEntity();
                    result.setEvaluationRun(run);
                    result.setCaseId(testCase.id());
                    result.setReviewJobId(job.getId());
                    result.setExpectedCount(score.expected());
                    result.setReportedCount(score.reported());
                    result.setTruePositives(score.truePositives());
                    result.setFalsePositives(score.falsePositives());
                    result.setFalseNegatives(score.falseNegatives());
                    result.setPassed(score.passed());
                    result.setNotes(score.notes());
                    caseResultRepository.save(result);

                    tp += score.truePositives();
                    fp += score.falsePositives();
                    fn += score.falseNegatives();
                    if (completed != null && completed.getDurationMs() != null) {
                        totalDuration += completed.getDurationMs();
                    }
                    done++;

                    run.setCompletedCases(done);
                    run.setTruePositives(tp);
                    run.setFalsePositives(fp);
                    run.setFalseNegatives(fn);
                    evaluationRunRepository.saveAndFlush(run);
                }
            }

            run.setPrecision(ratio(tp, tp + fp));
            run.setRecall(ratio(tp, tp + fn));
            run.setF1(f1(ratio(tp, tp + fp), ratio(tp, tp + fn)));
            run.setFalsePositiveRate(cases.isEmpty() ? 0.0 : (double) fp / cases.size());
            run.setMeanDurationMs(cases.isEmpty() ? 0 : totalDuration / cases.size());
            run.setStatus(EvaluationStatus.COMPLETED);
            run.setFinishedAt(Instant.now());
            evaluationRunRepository.saveAndFlush(run);
            log.info("evaluation {} complete: P={} R={} F1={}", runId, run.getPrecision(), run.getRecall(), run.getF1());

        } catch (Exception ex) {
            log.error("evaluation {} failed", runId, ex);
            run.setStatus(EvaluationStatus.FAILED);
            run.setErrorMessage(ex.getMessage());
            run.setFinishedAt(Instant.now());
            evaluationRunRepository.saveAndFlush(run);
        }
    }

    private Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private Double f1(Double precision, Double recall) {
        if (precision == null || recall == null || precision + recall == 0) {
            return null;
        }
        return 2 * precision * recall / (precision + recall);
    }
}
