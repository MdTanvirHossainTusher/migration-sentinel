package com.migrationsentinel.messaging;

import java.util.List;
import java.util.UUID;

/**
 * The messages that carry a queued job to whatever will execute it. The same records
 * are used as Spring {@code ApplicationEvent}s (local transport) and as the JSON body of
 * a Kafka record (kafka transport), so a job travels the same shape either way.
 */
public final class JobMessages {

    private JobMessages() {
    }

    /** Topic a review job is published on. */
    public static final String TOPIC_REVIEWS = "migration-sentinel.reviews";

    /** Topic an evaluation run is published on. */
    public static final String TOPIC_EVALUATIONS = "migration-sentinel.evaluations";

    /** Topic audit events are published on. */
    public static final String TOPIC_AUDIT = "migration-sentinel.audit";

    /** A persisted {@code review_job} row is ready to be executed. */
    public record ReviewSubmitted(UUID jobId) {
    }

    /** A persisted {@code evaluation_run} row is ready to be executed. */
    public record EvaluationSubmitted(UUID runId, List<String> caseIds) {
        public EvaluationSubmitted {
            caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
        }
    }
}
