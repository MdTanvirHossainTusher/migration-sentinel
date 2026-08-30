package com.migrationsentinel.messaging;

import java.util.List;
import java.util.UUID;

/**
 * How a controller hands a freshly persisted job off for execution. Called from inside the
 * submitting {@code @Transactional} method; every implementation guarantees the work is
 * triggered <em>after</em> that transaction commits, so the executor never races the write
 * and reads a row that is not there yet.
 *
 * <ul>
 *   <li>{@code local} — publishes a Spring event handled on an {@code AFTER_COMMIT} listener,
 *       executed in-process on a bounded pool. The default; used by tests and {@code bootRun}.</li>
 *   <li>{@code kafka} — writes a row to the transactional outbox in the same transaction; a
 *       relay ships it to Kafka once committed and a pool of consumers executes it. The
 *       compose stack default, and what lets the workers scale horizontally.</li>
 * </ul>
 */
public interface JobSubmissionGateway {

    void submitReview(UUID jobId);

    void submitEvaluation(UUID runId, List<String> caseIds);
}
