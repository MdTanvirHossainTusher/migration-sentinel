package com.migrationsentinel.messaging;

import com.migrationsentinel.service.ReviewRunner;
import com.migrationsentinel.service.eval.EvaluationRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * The one place a queued job is turned into a running one, whichever transport delivered it.
 * Both the in-process dispatcher and the Kafka consumer call through here so the execution
 * path — and its idempotency guarantees — is identical. The runners themselves no-op on a
 * job that is already terminal, so an at-least-once redelivery is harmless.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobExecutionService {

    private final ReviewRunner reviewRunner;
    private final EvaluationRunner evaluationRunner;

    public void executeReview(UUID jobId) {
        reviewRunner.runSync(jobId);
    }

    public void executeEvaluation(UUID runId, List<String> caseIds) {
        evaluationRunner.run(runId, caseIds);
    }
}
