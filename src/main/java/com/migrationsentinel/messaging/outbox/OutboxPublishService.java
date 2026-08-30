package com.migrationsentinel.messaging.outbox;

import com.migrationsentinel.model.entity.OutboxEventEntity;
import com.migrationsentinel.model.enums.OutboxStatus;
import com.migrationsentinel.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Relays outbox rows to Kafka. Each method is its own transaction so the row's status and
 * the send outcome commit together. At-least-once — consumers dedupe on the job id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sentinel.messaging", name = "transport", havingValue = "kafka")
public class OutboxPublishService {

    private static final int MAX_ERROR_LEN = 1000;

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${sentinel.messaging.outbox.max-retries:50}")
    private int maxRetries;

    @Value("${sentinel.messaging.outbox.send-timeout-ms:10000}")
    private long sendTimeoutMs;

    @Transactional
    public void publishOne(UUID id) {
        repository.findByIdAndStatus(id, OutboxStatus.PENDING).ifPresent(this::sendAndUpdate);
    }

    @Transactional
    public int sweepBatch(int limit) {
        List<OutboxEventEntity> batch = repository.lockDueBatch(Instant.now(), Limit.of(limit));
        batch.forEach(this::sendAndUpdate);
        return batch.size();
    }

    @SuppressWarnings("unchecked")
    private void sendAndUpdate(OutboxEventEntity e) {
        try {
            Map<String, Object> payload = objectMapper.readValue(e.getPayload(), Map.class);
            kafkaTemplate.send(e.getTopic(), e.getAggregateId(), payload)
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            repository.delete(e);
        } catch (Exception ex) {
            e.setRetryCount(e.getRetryCount() + 1);
            e.setLastError(truncate(ex.getMessage()));
            if (e.getRetryCount() >= maxRetries) {
                e.setStatus(OutboxStatus.FAILED);
                log.error("outbox event {} parked FAILED after {} retries: {}",
                        e.getId(), e.getRetryCount(), e.getLastError());
            } else {
                e.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds(e.getRetryCount())));
                log.warn("outbox event {} publish failed (retry {}): {}",
                        e.getId(), e.getRetryCount(), e.getLastError());
            }
            repository.save(e);
        }
    }

    private long backoffSeconds(int retryCount) {
        return Math.min(1L << Math.min(retryCount, 8), 300L);
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN);
    }
}
