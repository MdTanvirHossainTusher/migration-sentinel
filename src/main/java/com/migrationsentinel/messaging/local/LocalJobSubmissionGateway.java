package com.migrationsentinel.messaging.local;

import com.migrationsentinel.messaging.JobMessages;
import com.migrationsentinel.messaging.JobSubmissionGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * {@code sentinel.messaging.transport=local} (the default). Publishes a Spring event that a
 * transaction-bound listener picks up once the submitting transaction has committed.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sentinel.messaging", name = "transport",
        havingValue = "local", matchIfMissing = true)
public class LocalJobSubmissionGateway implements JobSubmissionGateway {

    private final ApplicationEventPublisher events;

    @Override
    public void submitReview(UUID jobId) {
        events.publishEvent(new JobMessages.ReviewSubmitted(jobId));
    }

    @Override
    public void submitEvaluation(UUID runId, List<String> caseIds) {
        events.publishEvent(new JobMessages.EvaluationSubmitted(runId, caseIds));
    }
}
