package com.migrationsentinel.messaging.outbox;

import com.migrationsentinel.messaging.JobMessages;
import com.migrationsentinel.messaging.JobSubmissionGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code sentinel.messaging.transport=kafka}. Records the job as an outbox row inside the
 * submitting transaction; the relay ships it to Kafka and a consumer pool executes it.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sentinel.messaging", name = "transport", havingValue = "kafka")
public class OutboxJobSubmissionGateway implements JobSubmissionGateway {

    private final OutboxRecorder recorder;

    @Override
    public void submitReview(UUID jobId) {
        recorder.record(JobMessages.TOPIC_REVIEWS, "review", jobId.toString(),
                "review.submitted", Map.of("jobId", jobId.toString()));
    }

    @Override
    public void submitEvaluation(UUID runId, List<String> caseIds) {
        recorder.record(JobMessages.TOPIC_EVALUATIONS, "evaluation", runId.toString(),
                "evaluation.submitted",
                Map.of("runId", runId.toString(), "caseIds", caseIds == null ? List.of() : caseIds));
    }
}
