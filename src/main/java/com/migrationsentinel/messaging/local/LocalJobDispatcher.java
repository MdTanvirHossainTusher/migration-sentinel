package com.migrationsentinel.messaging.local;

import com.migrationsentinel.messaging.JobExecutionService;
import com.migrationsentinel.messaging.JobMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs a queued job on a bounded pool once its submitting transaction has committed.
 *
 * <p>The whole point of the {@code AFTER_COMMIT} phase: the previous design fired an
 * {@code @Async} method straight from inside {@code @Transactional submit()}, so the worker
 * thread routinely opened its own transaction and looked up the run before the writer had
 * committed — {@code Optional.orElseThrow()} then threw {@code NoSuchElementException} and
 * the run was stuck QUEUED forever. Waiting for the commit removes the race.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sentinel.messaging", name = "transport",
        havingValue = "local", matchIfMissing = true)
public class LocalJobDispatcher {

    private final JobExecutionService executor;

    @Async("jobExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewSubmitted(JobMessages.ReviewSubmitted event) {
        log.debug("dispatching review {}", event.jobId());
        executor.executeReview(event.jobId());
    }

    @Async("jobExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEvaluationSubmitted(JobMessages.EvaluationSubmitted event) {
        log.debug("dispatching evaluation {}", event.runId());
        executor.executeEvaluation(event.runId(), event.caseIds());
    }
}
