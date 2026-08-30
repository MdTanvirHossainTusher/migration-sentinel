package com.migrationsentinel.messaging.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Hybrid relay: an immediate publish once the recording transaction commits (low latency),
 * plus a scheduled sweep that recovers rows the immediate path could not ship — broker was
 * down, this pod crashed, another pod recorded them. Both call the same idempotent send.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sentinel.messaging", name = "transport", havingValue = "kafka")
public class OutboxRelay {

    private final OutboxPublishService publishService;

    @Value("${sentinel.messaging.outbox.sweep-batch-size:200}")
    private int sweepBatchSize;

    @Async("outboxExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecorded(OutboxRecordedEvent event) {
        try {
            publishService.publishOne(event.outboxId());
        } catch (Exception ex) {
            log.warn("immediate outbox publish failed for {}; the sweep will retry", event.outboxId(), ex);
        }
    }

    @Scheduled(fixedDelayString = "${sentinel.messaging.outbox.sweep-interval-ms:5000}")
    public void sweep() {
        try {
            int n = publishService.sweepBatch(sweepBatchSize);
            if (n > 0) {
                log.debug("outbox sweep shipped {} event(s)", n);
            }
        } catch (Exception ex) {
            log.error("outbox sweep failed", ex);
        }
    }
}
